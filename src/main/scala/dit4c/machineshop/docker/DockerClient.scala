package dit4c.machineshop.docker

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.http._
import spray.json._
import dit4c.machineshop.docker.models.DockerContainer

class DockerClient(val baseUrl: spray.http.Uri) {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val SERVICE_PORT = 80

  // Overridden in unit tests
  def sendAndReceive: HttpRequest => Future[HttpResponse] =
    spray.client.pipelining.sendReceive

  def createContainer(name: String) = {
    import spray.httpx.ResponseTransformation._

    def parseJsonResponse: HttpResponse => DockerContainer = { res =>
      import spray.json._
      import DefaultJsonProtocol._

      val obj = JsonParser(res.entity.asString).convertTo[JsObject]

      DockerContainer(obj.getFields("Id").head.convertTo[String], name)
    }

    val pipeline: HttpRequest => Future[DockerContainer] =
      sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

    val createRequest =
      JsObject(
        "Tty" -> JsBoolean(true),
        "Dns" -> JsNull,
        "Image" -> JsString("dit4c/python"),
        "ExposedPorts" -> JsObject("80/tcp" -> JsObject())
      )

    pipeline {
      import spray.httpx.RequestBuilding._
      Post(baseUrl + s"containers/create?name=$name")
        .withEntity(HttpEntity(createRequest.compactPrint))
    }
  }

  def listContainers = {

    import spray.httpx.ResponseTransformation._

    def parseJsonResponse: HttpResponse => Set[DockerContainer] = { res =>
      import spray.json._
      import DefaultJsonProtocol._

      JsonParser(res.entity.asString)
        .convertTo[Seq[JsObject]]
        .map { obj: JsObject =>
          val Seq(jsId, namesWithSlashes) = obj.getFields("Id", "Names")
          // Get a single name without a slash
          val name: String = namesWithSlashes.convertTo[List[String]] match {
            case Seq(nameWithSlash: String) if nameWithSlash.startsWith("/") =>
              nameWithSlash.stripPrefix("/")
          }
          DockerContainer(jsId.convertTo[String], name)
        }
        .toSet
    }

    val pipeline: HttpRequest => Future[Set[DockerContainer]] =
      sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

    pipeline {
      import spray.httpx.RequestBuilding._
      Get(baseUrl + "containers/json?all=1")
    }

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