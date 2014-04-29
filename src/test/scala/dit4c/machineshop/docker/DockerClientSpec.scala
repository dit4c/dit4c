package dit4c.machineshop.docker

import scala.concurrent.Future
import scala.concurrent.Promise
import org.specs2.mutable.Specification
import akka.util.Timeout.intToTimeout
import spray.http.ContentTypes
import spray.http.HttpEntity
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.StatusCodes
import spray.http.Uri
import spray.util.pimpFuture
import dit4c.machineshop.docker.models._
import akka.util.Timeout
import java.util.concurrent.TimeUnit

class DockerClientSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  import spray.util.pimpFuture
  import dit4c.BetamaxUtils._

  def haveId(id: String) =
    beTypedEqualTo(id) ^^ ((_:DockerContainer).id aka "ID")

  def haveName(n: String) =
    beTypedEqualTo(n) ^^ ((_:DockerContainer).name aka "name")

  def beRunning = beTrue ^^ ((_: DockerContainer).isRunning aka "is running")
  def beStopped = beRunning.not

  "DockerClient" >> {

    "containers" >> {
      "create" in {
        withTape("DockerClient.containers.create") {
          val client = new DockerClient(Uri("http://localhost:4243/"))
          val dc = client.containers.create("testnew").await
          dc must (haveName("testnew") and beStopped)
          // Check we can't pass invalid names
          client.containers.create("test_new").await must
            throwA[IllegalArgumentException]
        }
      }
      "list" in {
        withTape("DockerClient.containers.list") {
          val client = new DockerClient(Uri("http://localhost:4243/"))
          client.containers.create("testlist-1").await
          client.containers.create("testlist-2").flatMap(_.start).await
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
          val client = new DockerClient(Uri("http://localhost:4243/"))
          val dc = client.containers.create("testrefresh").await
          val refreshed = dc.refresh.await
          refreshed must (haveId(dc.id)
              and haveName(dc.name)
              and beStopped
              and be(dc).not)
        }
      }
      "start" >> {
        withTape("DockerClient.container.start") {
          val client = new DockerClient(Uri("http://localhost:4243/"))
          val dc = client.containers.create("teststart").await
          val refreshed = dc.start.await
          refreshed must (haveId(dc.id)
              and haveName(dc.name)
              and beRunning)
        }
      }
      "stop" >> {
        val client = new DockerClient(Uri("http://localhost:4243/"))
        val dc = withTape("DockerClient.container.stop-setup") {
          client.containers.create("teststop").flatMap(_.start).await
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
    }
  }
}


