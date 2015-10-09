package dit4c.machineshop.docker

import scala.concurrent.Future
import scala.concurrent.Promise
import org.specs2.mutable.Specification
import akka.util.Timeout.intToTimeout
import dit4c.machineshop.docker.models._
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scalaz.IsEmpty
import akka.http.scaladsl.model.Uri
import scala.concurrent.Await

class DockerClientSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  import dit4c.BetamaxUtils._

  def newDockerClient = new DockerClientImpl(Uri("http://localhost:4243/"))

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
        withTape("DockerClient.images.pull") {
          val client = newDockerClient
          client.images.pull("busybox").await(Timeout(5, TimeUnit.MINUTES))
        }
        withTape("DockerClient.images.list") {
          val client = newDockerClient
          val images = client.images.list.await
          images must contain(haveImageName("busybox:latest"))
        }
      }
    }

    "containers" >> {
      "create" in {
        withTape("DockerClient.containers.create") {
          val client = newDockerClient
          val dc = client.containers.create("testnew", image).await
          dc must (haveName("testnew") and beStopped)
          // Check we can't pass invalid names
          client.containers.create("test_new", image).await must
            throwA[IllegalArgumentException]
        }
      }
      "list" in {
        withTape("DockerClient.containers.list") {
          val client = newDockerClient
          client.containers.create("testlist-1", image).await
          client.containers.create("testlist-2", image).flatMap(_.start).await
          val containers = client.containers.list.await
          containers must contain(allOf(
            haveName("testlist-1") and beStopped,
            haveName("testlist-2") and beRunning))
        }
      }
    }

    "container" >> {
      "refresh" >> {
        withTape("DockerClient.container.refresh") {
          val client = newDockerClient
          val dc = client.containers.create("testrefresh", image).await
          val refreshed = dc.refresh.await
          refreshed must (haveId(dc.id)
              and haveName(dc.name)
              and beStopped
              and be(dc).not)
        }
      }
      "start" >> {
        withTape("DockerClient.container.start") {
          val client = newDockerClient
          val dc = client.containers.create("teststart", image).await
          val refreshed = dc.start.await
          refreshed must (haveId(dc.id)
              and haveName(dc.name)
              and beRunning)
        }
      }
      "stop" >> {
        val client = newDockerClient
        val dc = withTape("DockerClient.container.stop-setup") {
          client.containers.create("teststop", image).flatMap(_.start).await
        }
        dc must (haveId(dc.id)
            and haveName(dc.name)
            and beRunning)
        val refreshed = withTape("DockerClient.container.stop-run") {
          dc.stop().await
        }
        refreshed must (haveId(dc.id)
            and haveName(dc.name)
            and beStopped)
      }
      "delete" >> {
        val client = newDockerClient
        val dc = withTape("DockerClient.container.delete-setup") {
          val dc = client.containers.create("testdelete", image).await
          val cs = client.containers.list.await
          cs must contain(haveId(dc.id))
          dc
        }
        withTape("DockerClient.container.delete-run") {
          dc.delete.await
          val cs = client.containers.list.await
          cs must not contain(haveId(dc.id))
        }
      }
    }
  }
}


