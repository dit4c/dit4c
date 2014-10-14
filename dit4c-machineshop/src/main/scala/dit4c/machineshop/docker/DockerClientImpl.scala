package dit4c.machineshop.docker

import scala.concurrent.{Future, future}
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.http._
import spray.http.ContentTypes._
import spray.json._
import dit4c.machineshop.docker.models._
import java.util.{Calendar, TimeZone}

class DockerClientImpl(val baseUrl: spray.http.Uri) extends DockerClient {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val SERVICE_PORT = 80

  // Overridden in unit tests
  def sendAndReceive: HttpRequest => Future[HttpResponse] =
    spray.client.pipelining.sendReceive

  override val images = ImagesImpl
  override val containers = ContainersImpl

  case class ImageImpl(
      val id: String,
      val names: Set[String],
      val created: Calendar) extends DockerImage

  object ImagesImpl extends DockerImages {

    def list = {
      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => List[DockerImage] = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        for {
          obj <- JsonParser(res.entity.asString).convertTo[List[JsObject]]
          dockerImage = obj match {
            case JsObject(fields) =>
              ImageImpl(
                  fields("Id").convertTo[String],
                  fields("RepoTags").convertTo[Set[String]]
                    .filter(_ != "<none>:<none>"),
                  {
                    val c = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
                    val epochSeconds = fields("Created").convertTo[Long]
                    c.setTimeInMillis(epochSeconds * 1000)
                    c
                  })
          }
          namedImage <- Some(dockerImage).filter(!_.names.isEmpty)
        } yield namedImage
      }

      val pipeline: HttpRequest => Future[Seq[DockerImage]] =
        sendAndReceive ~>
        logResponse(log, Logging.DebugLevel) ~>
        parseJsonResponse

      pipeline({
        import spray.httpx.RequestBuilding._
        Get(baseUrl + "images/json")
      })
    }

    def pull(imageName: String, tagName: String) = {
      import spray.httpx.ResponseTransformation._

      def urlEncode(s: String) = java.net.URLEncoder.encode(s, "utf-8")

      def parseResponse: HttpResponse => Unit = { res =>
        res.status match {
          case StatusCodes.InternalServerError =>
            throw new Exception(
                "Pull failed due to server error:\n\n"+res.entity.asString)
          case _: StatusCode =>
            Unit
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      pipeline({
        import spray.httpx.RequestBuilding._
        Post(baseUrl + ("images/create?fromImage=%s&tag=%s".format(
          urlEncode(imageName), urlEncode(tagName)
        )))
      })
    }

  }



  class ContainerImpl(val id: String, val name: String, val status: ContainerStatus) extends DockerContainer {

    override def refresh = {

      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => DockerContainer = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        val obj = JsonParser(res.entity.asString).convertTo[JsObject]

        val status =
          if (obj.fields("State").asJsObject.fields("Running").convertTo[Boolean])
            ContainerStatus.Running
          else
            ContainerStatus.Stopped
        new ContainerImpl(id, name, status)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      pipeline {
        import spray.httpx.RequestBuilding._
        Get(baseUrl + s"containers/$id/json")
      }

    }

    override def start = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      val createRequest =
        JsObject(
          "PortBindings" -> JsObject(),
          "PublishAllPorts" -> JsBoolean(true)
        )

      pipeline({
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/$id/start")
          .withEntity(HttpEntity(`application/json`, createRequest.compactPrint))
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    override def stop(timeout: Duration) = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        if (res.status == StatusCodes.NotFound) {
          throw new Exception("Container does not exist")
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      // Cannot have negative timeout
      val t = Math.max(0, timeout.toSeconds)

      pipeline({
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/$id/stop?t=$t")
      }).flatMap({
        case _: Unit => this.refresh
      })
    }

    override def delete = {
      import spray.httpx.ResponseTransformation._

      def parseResponse: HttpResponse => Unit = { res =>
        res.status match {
          case StatusCodes.NotFound =>
            throw new Exception("Container does not exist")
          case StatusCodes.InternalServerError =>
            throw new Exception(
                "Deletion failed due to server error:\n\n"+res.entity.asString)
          case _: StatusCode => Unit
        }
      }

      val pipeline: HttpRequest => Future[Unit] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseResponse

      pipeline({
        import spray.httpx.RequestBuilding._
        Delete(baseUrl + s"containers/$id?v=1")
      })
    }

  }

  object ContainersImpl extends DockerContainers {

    override def create(name: String, image: DockerImage) = {
      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => DockerContainer = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        val obj = JsonParser(res.entity.asString).convertTo[JsObject]

        new ContainerImpl(obj.getFields("Id").head.convertTo[String], name,
            ContainerStatus.Stopped)
      }

      val pipeline: HttpRequest => Future[DockerContainer] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      val createRequest =
        JsObject(
          "Hostname" -> JsString(name),
          "Tty" -> JsBoolean(true),
          "AttachStdout" -> JsBoolean(true),
          "AttachStderr" -> JsBoolean(true),
          "Dns" -> JsNull,
          "Image" -> JsString(image),
          "ExposedPorts" -> JsObject("80/tcp" -> JsObject())
        )

      pipeline {
        if (!name.isValidContainerName) {
          throw new IllegalArgumentException(
              "Name must be a valid lower-case DNS label")
        }
        import spray.httpx.RequestBuilding._
        Post(baseUrl + s"containers/create?name=$name")
          .withEntity(HttpEntity(createRequest.compactPrint))
      }
    }

    override def list = {

      import spray.httpx.ResponseTransformation._

      def parseJsonResponse: HttpResponse => Seq[DockerContainer] = { res =>
        import spray.json._
        import DefaultJsonProtocol._

        JsonParser(res.entity.asString)
          .convertTo[Seq[JsObject]]
          .map { obj: JsObject =>
            val Seq(jsId, namesWithSlashes, jsStatus) =
              obj.getFields("Id", "Names", "Status")
            // Get the first name, without the slash
            // (Multiple names are possible, but first should be native name.)
            val name: String = namesWithSlashes.convertTo[List[String]] match {
              case nameWithSlash :: _ if nameWithSlash.startsWith("/") =>
                nameWithSlash.stripPrefix("/")
            }
            val status =
              if (jsStatus.convertTo[String].matches("Up .*"))
                ContainerStatus.Running
              else
                ContainerStatus.Stopped
            new ContainerImpl(jsId.convertTo[String], name, status)
          }
          .filter(_.name.isValidContainerName)
      }

      val pipeline: HttpRequest => Future[Seq[DockerContainer]] =
        sendAndReceive ~> logResponse(log, Logging.DebugLevel) ~> parseJsonResponse

      pipeline {
        import spray.httpx.RequestBuilding._
        Get(baseUrl + "containers/json?all=1")
      }

    }

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