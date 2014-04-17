package dit4c.machineshop.docker

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
  import DockerClient._

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

    val pipeline: HttpRequest => Future[Set[DockerContainer]] =
      sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

    pipeline {
      import spray.httpx.RequestBuilding._
      Get(baseUrl + "containers/json?all=1")
    }

  }

  def parseJsonResponse: HttpResponse => Set[DockerContainer] = { (res: HttpResponse) =>
    import spray.json._
    import DefaultJsonProtocol._

    val objs = JsonParser(res.entity.asString).convertTo[Seq[JsObject]]

    objs.map { obj: JsObject =>
      val Seq(jsId, namesWithSlashes) = obj.getFields("Id", "Names")
      // Get a single name without a slash
      val name: String = namesWithSlashes.convertTo[List[String]] match {
        case Seq(nameWithSlash: String) if nameWithSlash.startsWith("/") =>
          nameWithSlash.stripPrefix("/")
      }
      DockerContainer(jsId.convertTo[String], name)
    }.toSet
  }

  implicit class ProjectNameTester(str: String) {

    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidProjectName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }

  }

}

object DockerClient {

  case class DockerContainer(id: String, val name: String)

}