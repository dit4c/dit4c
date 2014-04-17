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

class DockerClientSpec extends Specification {

  import spray.util.pimpFuture
  import dit4c.BetamaxUtils._

  "DockerClient" should {

    "parse container response correctly" in {
      val client = new DockerClient(Uri("http://localhost:4243/"))
      withTape("DockerClient.containerPorts") {
        import DockerClient._
        val response = client.containerPorts.await(2000)
        response must contain(DockerContainer(
            "6e6a24e5a6a5012b2ba868ef9868d2a5eadd6ed1f52feef480603909d3699e50",
            "test1"))
        response must contain(DockerContainer(
            "2d9616a27e09efd75117bb68857d77ab3c06dff6ec4e930610270bca10820ec2",
            "bar"))
      }
    }
  }
}


