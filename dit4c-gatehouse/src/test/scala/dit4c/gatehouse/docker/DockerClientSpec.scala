package dit4c.gatehouse.docker

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util._
import org.specs2.mutable.Specification
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.LogContainerResultCallback
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import akka.util.Timeout
import java.nio.file.Files
import akka.stream.scaladsl.Source
import com.github.dockerjava.api.async.ResultCallback
import java.io.Closeable
import scala.concurrent.Promise
import org.specs2.specification.core.Fragments

class DockerClientSpec extends Specification {
  sequential

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val actorSystem = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val timeout = new Timeout(30, TimeUnit.SECONDS)

  trait DockerInDockerInstance {
    def newClient: DockerClient
    def newDirectClient: com.github.dockerjava.api.DockerClient
    def destroy: Unit
  }

  class FutureCallback extends ResultCallback[Frame] {
    protected val promise = Promise[ByteString]()
    protected var bytes = ByteString()
    def future = promise.future

    def onStart(closeable: Closeable) {}

    /** Called when an async result event occurs */
    def onNext(obj: Frame) { bytes ++= ByteString(obj.getPayload) }

    /** Called when an exception occurs while processing */
    def onError(throwable: Throwable) {
      promise.failure(throwable)
    }

    /** Called when processing was finished either by reaching the end or by aborting it */
    def onComplete() {
      promise.success(bytes)
    }

    def close() {}
  }

  def setupDockerInDocker: DockerInDockerInstance = {
    val hostDockerUri = "unix:///var/run/docker.sock"
    val client = DockerClientBuilder.getInstance(hostDockerUri).build
    val tmpDir = Files.createTempDirectory("dit4c_gh_dind_test_")
    tmpDir.toFile.deleteOnExit
    val dindId = client.createContainerCmd("docker.io/docker:1.9-dind")
      .withPrivileged(true)
      .withAttachStdout(true)
      .withAttachStderr(true)
      .withName("dit4c_gh_dind_test-"+Random.alphanumeric.take(8).mkString)
      .withBinds(new Bind(tmpDir.toAbsolutePath.toString, new Volume("/var/run")))
      .exec
      .getId
    client.startContainerCmd(dindId).exec
    client.logContainerCmd(dindId)
      .withStdOut(true).withStdErr(true).withFollowStream(true)
      .exec(new LogContainerResultCallback() {
        override def onNext(frame: Frame) = {
          val s = new String(frame.getPayload, "utf-8")
          if (s.contains("Docker daemon")) {
            println(s.trim)
            close
          }
        }
      })
      .awaitCompletion(2, TimeUnit.MINUTES)
    val execCmd = client.execCreateCmd(dindId)
      .withCmd("sh","-c","chmod a+rwx /var/run/docker.sock")
      .withAttachStdout(true)
      .withAttachStderr(true)
      .exec
    client.execStartCmd(execCmd.getId).exec(new FutureCallback()).future
      .foreach(_.decodeString("utf-8"))
    new DockerInDockerInstance() {
      val uri = new java.net.URI(s"unix://${tmpDir.toAbsolutePath}/docker.sock")
      def newClient = DockerClient(Some(uri))
      def newDirectClient = DockerClientBuilder.getInstance(uri.toASCIIString).build
      override def destroy {
        client.removeContainerCmd(dindId)
          .withForce(true)
          .withRemoveVolumes(true)
          .exec
      }
    }
  }

  var dind: Either[Throwable,DockerInDockerInstance] = null

  def directClient = dind.right.get.newDirectClient

  def beforeAll = {
    dind = Try(setupDockerInDocker) match {
      case Success(d) =>
        d.newDirectClient.pullImageCmd(image).exec(new ResultCallback[PullResponseItem]() {
          protected val promise = Promise[Unit]()
          def future = promise.future
          def onStart(closeable: Closeable) {}
          /** Called when an async result event occurs */
          def onNext(obj: PullResponseItem) {}
          /** Called when an exception occurs while processing */
          def onError(throwable: Throwable) { promise.failure(throwable) }
          /** Called when processing was finished either by reaching the end or by aborting it */
          def onComplete() { promise.success(()) }
          def close() {}
        }).future.await
        Right(d)
      case Failure(e) => Left(e)
    }
  }

  def afterAll = {
    dind.right.foreach(_.destroy)
  }

  override def map(fs: => Fragments) = step(beforeAll) ^ fs ^ step(afterAll)

  def newDockerClient: DockerClient = dind match {
    case Right(d) => d.newClient
    case Left(e) => skipped("skipped: "+e.getMessage); null
  }

  implicit class FutureAwaitable[T](f: Future[T]) {
    def await(implicit timeout: Timeout) = Await.result(f, timeout.duration)
  }

  val image = "busybox:latest"

  "DockerClient" >> {

    "events" >> {
      var events = Seq.empty[DockerClient.ContainerEvent]
      val client = newDockerClient
      val (fStart, fComplete) = client.events { e =>
        events :+= e
      }
      try {
        events must beEmpty
        val container = directClient.createContainerCmd(image)
          .withName("testevents")
          .withExposedPorts(new ExposedPort(8888, InternetProtocol.TCP))
          .withCmd("/bin/sh","-c","while true; do echo foo; sleep 1; done")
          .exec
        directClient.startContainerCmd(container.getId).exec
        Thread.sleep(100)
        events must haveSize(1)
        Thread.sleep(100)
        directClient.restartContainerCmd(container.getId).exec
        Thread.sleep(100)
        events must haveSize(4)
        directClient.killContainerCmd(container.getId).exec
        directClient.removeContainerCmd(container.getId).withForce(true).exec
        Thread.sleep(100)
        events must haveSize(5)
        directClient.tagImageCmd(image, "testevents", "test").exec
        directClient.removeImageCmd("testevents:test").exec
        Thread.sleep(100)
        events must haveSize(5)
        events.map(_.eventType) must_== Seq("start","die","stop","start","die")
      } finally {
        // Close event feed
        fStart.foreach(_.close)
        // Wait on close
        fComplete.await
        done
      }
    }

    "container IDs" >> {
      val client = newDockerClient
      val containers = 1.to(50).map { i =>
        val containerId = directClient.createContainerCmd(image)
          .withName(s"testports-$i")
          .withExposedPorts(new ExposedPort(8888, InternetProtocol.TCP))
          .withCmd("/bin/sh","-c","while true; do echo foo; sleep 5; done")
          .exec
          .getId
        directClient.startContainerCmd(containerId).exec
        containerId
      }
      val retrievedContainerIds = client.containerIds.await
      retrievedContainerIds must_== containers.toSet
    }
  }
}


