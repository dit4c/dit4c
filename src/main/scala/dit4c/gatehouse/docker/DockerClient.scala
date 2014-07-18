package dit4c.gatehouse.docker

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.http._
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject

class DockerClient(val baseUrl: spray.http.Uri) {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val SERVICE_PORT = 80

  // Overridden in unit tests
  def sendAndReceive: HttpRequest => Future[HttpResponse] =
    spray.client.pipelining.sendReceive

  def containerPorts = {

    import spray.httpx.ResponseTransformation._

    val pipeline: HttpRequest => Future[Map[String, Int]] =
      sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

    pipeline {
      import spray.httpx.RequestBuilding._
      Get(baseUrl + "containers/json")
    }

  }

  def parseJsonResponse: HttpResponse => Map[String, Int] = { (res: HttpResponse) =>
    import spray.json._
    import DefaultJsonProtocol._

    val objs = JsonParser(res.entity.asString).convertTo[Seq[JsObject]]

    var pairs = objs.map { obj: JsObject =>
      var Seq(namesWithSlashes, portArr) = obj.getFields("Names", "Ports")
      // Get a single name without a slash
      var name = namesWithSlashes.convertTo[List[String]] match {
        case Seq(nameWithSlash: String) if nameWithSlash.startsWith("/") =>
          nameWithSlash.stripPrefix("/")
      }

      def hasRightPort(portObj: JsObject) = {
        val fields = portObj.fields
        fields.contains("PrivatePort") &&
        fields.contains("PublicPort") &&
        fields("PrivatePort").convertTo[Int] == SERVICE_PORT
      }

      var port: Option[Int] = portArr.asInstanceOf[JsArray].elements collectFirst {
        case jsObj: JsObject if hasRightPort(jsObj) =>
          jsObj.fields("PublicPort").convertTo[Int]
      }

      port.map((name, _))
    }
    pairs.flatten.filter(_._1.isValidContainerName).toMap
  }

  implicit class ContainerNameTester(str: String) {

    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidContainerName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }

  }

}

