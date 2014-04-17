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

  def client = new DockerClient(Uri("http://localhost:4243/")) {

    val mockJson =
      """
      [{"Command":"/usr/bin/supervisord -c /opt/supervisord.conf","Created":1395720358,"Id":"4dd43440ed603d8a7e15bed9ccaa4a32c8220121ad59090dbdc7cc9d36fa6588","Image":"dit4c/python:latest","Names":["/noports"],"Ports":[{"PublicPort":22,"Type":"tcp"},{"PublicPort":80,"Type":"tcp"}],"Status":"Up 6 seconds"}
,{"Command":"/usr/bin/supervisord -c /opt/supervisord.conf","Created":1395720334,"Id":"146108dae32ad15862eab00080c14f083426b68f3eccda9418a679b7a40ceab9","Image":"dit4c/python:latest","Names":["/abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl"],"Ports":[{"IP":"0.0.0.0","PrivatePort":22,"PublicPort":49155,"Type":"tcp"},{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":49156,"Type":"tcp"}],"Status":"Up 30 seconds"}
,{"Command":"/usr/bin/supervisord -c /opt/supervisord.conf","Created":1395711022,"Id":"67c59304c4c3cb0f9fd597079b170451e89dec41329e47df56df904b5f2b6c53","Image":"dit4c/python:latest","Names":["/abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijk"],"Ports":[{"IP":"0.0.0.0","PrivatePort":22,"PublicPort":49153,"Type":"tcp"},{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":49154,"Type":"tcp"}],"Status":"Up 2 hours"}
]
      """

    val mockResponse = HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, mockJson.getBytes))

    override def sendAndReceive: HttpRequest => Future[HttpResponse] ={
      (req: HttpRequest) => Promise.successful(mockResponse).future
    }
  }

  "DockerClient" should {

    "parse container response correctly" in {
      val response = client.containerPorts.await(2000)
      response must_== Map("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijk" -> 49154)
    }
  }
}


