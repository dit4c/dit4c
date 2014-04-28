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

class DockerClientSpec extends Specification {

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
      "list" in {
        withTape("DockerClient.listContainers") {
          val client = new DockerClient(Uri("http://localhost:4243/"))
          val containers = client.containers.list.await(2000)
          containers must contain(allOf(
            haveId("6e6a24e5a6a5012b2ba868ef9868d2a5eadd6ed1f52feef480603909d3699e50")
              and haveName("test1")
              and beRunning,
            haveId("2d9616a27e09efd75117bb68857d77ab3c06dff6ec4e930610270bca10820ec2")
              and haveName("bar")
              and beStopped))
        }
      }
      "create" in {
        withTape("DockerClient.createContainer") {
          val client = new DockerClient(Uri("http://localhost:4243/"))
          val dc = client.containers.create("testnew").await(2000)
          dc must (haveId("1667d4047620b5e2961e155add815ad54ba77a221b328ea14dacb8b44a55d36b")
            and haveName("testnew")
            and beStopped)
          // Check we can't pass invalid names
          client.containers.create("test_new").await(2000) must
            throwA[IllegalArgumentException]
        }
      }
    }

  }
}


