package dit4c.machineshop.docker

import scala.concurrent._
import scala.util._
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import akka.util.Timeout.intToTimeout
import dit4c.machineshop.docker.models._
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scalaz.IsEmpty
import akka.http.scaladsl.model.Uri
import scala.concurrent.Await
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.api.model._
import scala.util._
import com.github.dockerjava.core.command.LogContainerResultCallback
import java.io.Closeable

class DockerClientSpec extends Specification with BeforeAfterAll {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  trait DockerInDockerInstance {
    def newClient: DockerClient
    def destroy: Unit
  }

  def setupDockerInDocker: DockerInDockerInstance = {
    val hostDockerUri = "unix:///var/run/docker.sock"
    val client = DockerClientBuilder.getInstance(hostDockerUri).build
    val dindId = client.createContainerCmd("docker.io/jpetazzo/dind")
      .withPrivileged(true)
      .withAttachStdout(true)
      .withAttachStderr(true)
      .withName("dit4c_mc_dind_test-"+Random.alphanumeric.take(8).mkString)
      .withEnv(
        "PORT=2375",
        "DOCKER_DAEMON_ARGS=--storage-driver=vfs")
      .withExposedPorts(ExposedPort.tcp(2375))
      .withPublishAllPorts(true)
      .exec
      .getId
    client.startContainerCmd(dindId).exec
    val dindPort = client.inspectContainerCmd(dindId).exec
      .getNetworkSettings.getPorts.getBindings.get(ExposedPort.tcp(2375))
      .toIterable.head.getHostPort
    client.logContainerCmd(dindId).withStdOut.withStdErr.withFollowStream
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
    new DockerInDockerInstance() {
      def newClient = new DockerClientImpl(s"http://localhost:$dindPort/")
      override def destroy {
        client.removeContainerCmd(dindId).withForce.exec
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

  val image = "busybox"

  // This spec must be run sequentially
  sequential

  "DockerClient" >> {
    "images" >> {
      "pull and list" in {
        val client = newDockerClient
        client.images.pull("busybox").await(Timeout(5, TimeUnit.MINUTES))
        val images = client.images.list.await
        images must contain(haveImageName("busybox:latest"))
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


