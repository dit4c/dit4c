package dit4c.machineshop.docker

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util._
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.LogContainerResultCallback
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import akka.util.Timeout
import dit4c.machineshop.docker.models.DockerContainer
import dit4c.machineshop.docker.models.DockerImage
import java.nio.file.Files
import akka.stream.scaladsl.Source
import com.github.dockerjava.api.async.ResultCallback
import java.io.Closeable
import scala.concurrent.Promise
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder
import com.github.dockerjava.core.DockerClientConfig

class DockerClientSpec extends Specification with BeforeAfterAll {
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
    val client = DockerClientBuilder.getInstance.build
    val tmpDir = Files.createTempDirectory("dit4c_mc_dind_test_")
    tmpDir.toFile.deleteOnExit
    val dindId = client.createContainerCmd("docker.io/docker:1.9-dind")
      .withPrivileged(true)
      .withAttachStdout(true)
      .withAttachStderr(true)
      .withName(tmpDir.getFileName.toString)
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
    val execOutput =
      client.execStartCmd(execCmd.getId).exec(new FutureCallback()).future.await
    println(execOutput.decodeString("utf-8"))
    new DockerInDockerInstance() {
      val uri = new java.net.URI(s"unix://${tmpDir.toAbsolutePath}/docker.sock")
      def newClient = DockerClientImpl(Some(uri))
      def newDirectClient = DockerClientBuilder.getInstance({
        DockerClientConfig.createDefaultConfigBuilder
          .withDockerTlsVerify(false)
          .withDockerHost(uri.toASCIIString)
      }).build
      override def destroy {
        client.removeContainerCmd(dindId)
          .withForce(true)
          .withRemoveVolumes(true)
          .exec
      }
    }
  }

  var dind: Either[Throwable,DockerInDockerInstance] = null

  def beforeAll = {
    dind = Try(setupDockerInDocker) match {
      case Success(d) => Right(d)
      case Failure(e) => Left(e)
    }
  }

  def afterAll = {
    dind.right.foreach(_.destroy)
  }

  def newDockerClient: DockerClient = dind match {
    case Right(d) => d.newClient
    case Left(e) => skipped("skipped: "+e.getMessage); null
  }


  def haveId(id: String) =
    beTypedEqualTo(id) ^^ ((_:DockerContainer).id aka "ID")

  def haveName(n: String) =
    beTypedEqualTo(n) ^^ ((_:DockerContainer).name aka "name")

  def beRunning = beTrue ^^ ((_: DockerContainer).isRunning aka "is running")
  def beStopped = beRunning.not

  def haveImageName(n: String) =
    contain(beTypedEqualTo(n)) ^^ ((_:DockerImage).names aka "names")

  implicit class FutureAwaitable[T](f: Future[T]) {
    def await(implicit timeout: Timeout) = Await.result(f, timeout.duration)
  }

  val image = "busybox:latest"

  // This spec must be run sequentially
  sequential

  "DockerClient" >> {
    "images" >> {
      "pull and list" in {
        val client = newDockerClient
        val (imageName, tagName) = Some(image.span(_ != ':'))
          .map(v => (v._1, v._2.stripPrefix(":")))
          .get
        client.images.pull(imageName, tagName).await(Timeout(5, TimeUnit.MINUTES))
        val images = client.images.list.await
        images must contain(haveImageName(image))
      }
    }

    "containers" >> {
      "create" in {
        val client = newDockerClient
        val dc = client.containers.create("testnew", image).await
        dc must (haveName("testnew") and beStopped)
        // Check we can't pass invalid names
        client.containers.create("test_new", image).await must
          throwA[IllegalArgumentException]
      }
      "list" in {
        val client = newDockerClient
        client.containers.create("testlist-1", image).await
        client.containers.create("testlist-2", image).flatMap(_.start).await
        val containers = client.containers.list.await
        containers must contain(allOf(
          haveName("testlist-1") and beStopped,
          haveName("testlist-2") and beRunning))
      }
    }

    "container" >> {
      "refresh" >> {
        val client = newDockerClient
        val dc = client.containers.create("testrefresh", image).await
        val refreshed = dc.refresh.await
        refreshed must (haveId(dc.id)
            and haveName(dc.name)
            and beStopped
            and be(dc).not)
      }
      "start" >> {
        val client = newDockerClient
        val dc = client.containers.create("teststart", image).await
        val refreshed = dc.start.await
        refreshed must (haveId(dc.id)
            and haveName(dc.name)
            and beRunning)
      }
      "stop" >> {
        val client = newDockerClient
        val dc =
          client.containers.create("teststop", image).flatMap(_.start).await
        dc must (haveId(dc.id)
            and haveName(dc.name)
            and beRunning)
        val refreshed =
          dc.stop().await
        refreshed must (haveId(dc.id)
            and haveName(dc.name)
            and beStopped)
      }
      "export" >> {
        val client = newDockerClient
        val dc =
          client.containers.create("testexport", image).await
        dc must (haveId(dc.id)
            and haveName(dc.name)
            and beStopped)
        val numImages = dind.right.get.newDirectClient.listImagesCmd.exec.size
        val exportSource = dc.export
        val sink = Sink.fold[Long,ByteString](0L) { (count, str) =>
          count + str.length
        }
        val (expectedSize, actualSize) = exportSource.toMat(sink)({
          case (fExpected, fActual) =>
            for { e <- fExpected; a <- fActual } yield (e,a)
        }).run.await
        actualSize must_== expectedSize
        val images = dind.right.get.newDirectClient.listImagesCmd.exec
        images.size must_== numImages
        actualSize must beGreaterThan[scala.Long](images.get(0).getVirtualSize)
      }
      "delete" >> {
        val client = newDockerClient
        val dc = {
          val dc = client.containers.create("testdelete", image).await
          val cs = client.containers.list.await
          cs must contain(haveId(dc.id))
          dc
        }
        dc.delete.await
        val cs = client.containers.list.await
        cs must not contain(haveId(dc.id))
      }
    }
  }
}


