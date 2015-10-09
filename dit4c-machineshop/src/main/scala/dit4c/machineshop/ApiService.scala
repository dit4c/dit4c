package dit4c.machineshop

import akka.actor._
import akka.pattern.ask
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._
import spray.json._
import scala.collection.JavaConversions._
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerContainer
import dit4c.machineshop.images._
import scala.util._
import scala.concurrent.Future
import akka.util.Timeout
import java.util.Calendar
import java.text.SimpleDateFormat
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.model.StatusCodes
import akka.stream.Materializer
import akka.http.scaladsl.model.HttpRequest
import scala.concurrent.ExecutionContext
import akka.stream.ActorMaterializer
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpEntity

class ApiService(
    arf: ActorRefFactory,
    client: DockerClient,
    imageMonitor: ActorRef,
    signatureActor: Option[ActorRef])
    extends RouteProvider {
  import scala.concurrent.duration._
  import dit4c.machineshop.images.ImageManagementActor._

  implicit val timeout = Timeout(10.seconds)

  import dit4c.machineshop.auth.SignatureActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  import ApiService.NewContainerRequest
  import ApiService.NewImageRequest
  import ApiService.marshallers._

  implicit val actorRefFactory = arf
  implicit val mat = ActorMaterializer()

  implicit val newContainerRequestUnmarshaller: FromRequestUnmarshaller[NewContainerRequest] =
    fromRequestUnmarshaller(sprayJsonUnmarshaller(newContainerRequestReader, mat))

  implicit val newImageRequestUnmarshaller: FromRequestUnmarshaller[NewImageRequest] =
    fromRequestUnmarshaller(sprayJsonUnmarshaller(newImageRequestReader, mat))

  def withContainer(name: String)(f: DockerContainer => Route): Route =
    onSuccess(client.containers.list) { containers =>
      containers.find(c => c.name == name) match {
        case Some(c) => f(c)
        case None => complete(StatusCodes.NotFound)
      }
    }

  def withImage(id: String)(f: Image => Route): Route =
    onSuccess(imageMonitor ? ListImages()) {
      case ImageList(images, _) => images.find(_.id == id) match {
        case Some(c) => f(c)
        case None => complete(StatusCodes.NotFound)
      }
    }

  def signatureCheck(f: => Route): Route =
    signatureActor
      .map { actor =>
        headerValueByName("Authorization") { _ =>
          extractRequest { request =>
            val sigCheck: Future[AuthResponse] =
              (actor ask AuthCheck(request)).map(_.asInstanceOf[AuthResponse])
            onSuccess(sigCheck) {
              case AccessGranted =>
                f
              case AccessDenied(msg) =>
                val challengeHeaders = Nil
                overrideStatusCode(StatusCodes.Forbidden) {
                  complete(msg)
                }
            }
          }
        } ~
        provide("Authorization required using HTTP Signature.") { msg =>
          val challengeHeaders = `WWW-Authenticate`(
              HttpChallenge("Signature", "",
                  Map("headers" -> "(request-target) date"))) :: Nil
          overrideStatusCode(StatusCodes.Unauthorized) {
            respondWithHeaders(challengeHeaders) {
              complete(msg)
            }
          }
        }
      }
      .getOrElse( f )

  val route: RequestContext => Future[RouteResult] =
    pathPrefix("containers") {
      pathEndOrSingleSlash {
        get {
          onSuccess(client.containers.list) { containers =>
            complete {
              containers
            }
          }
        } ~
        post {
          entity(as[NewContainerRequest]) { npr =>
            signatureCheck {
              onSuccess(client.containers.create(npr.name, npr.image)) { container =>
                overrideStatusCode(StatusCodes.Created) {
                  complete(container)
                }
              }
            }
          }
        }
      } ~
      pathPrefix("[a-z0-9\\-]+".r) { (name: String) =>
        pathEnd {
          get {
            withContainer(name) { container =>
              complete(container)
            }
          } ~
          delete {
            signatureCheck {
              withContainer(name) { container =>
                onComplete(container.delete) {
                  case _: Success[Unit] => complete(StatusCodes.NoContent)
                  case Failure(e) =>
                    overrideStatusCode(StatusCodes.InternalServerError) {
                      complete(e.getMessage)
                    }
                }
              }
            }
          }
        } ~
        path("start") {
          post {
            signatureCheck {
              withContainer(name) { container =>
                onSuccess(container.start) { container =>
                  complete(container)
                }
              }
            }
          }
        } ~
        path("stop") {
          post {
            signatureCheck {
              withContainer(name) { container =>
                onSuccess(container.stop()) { container =>
                  complete(container)
                }
              }
            }
          }
        }/* ~
        path("export") {
          get {
            signatureCheck {
              withContainer(name) { container =>
                (ctx) => {
                  val fn = (chunk: HttpMessagePart) => {
                    (ctx.responder ? chunk.withAck("ok")).map(_ => ())
                  }
                  // container.export will pass message chunks directly through
                  container.export(fn)
                }
              }
            }
          }
        }*/
      }
    } ~
    pathPrefix("images") {
      pathEndOrSingleSlash {
        get {
          onSuccess(imageMonitor ? ListImages()) {
            case ImageList(images, stateId) =>
              val etag = EntityTag(stateId)
              optionalHeaderValueByType[`If-None-Match`]() {
                case Some(`If-None-Match`(EntityTagRange.Default(tags)))
                    if tags.contains(etag) =>
                  overrideStatusCode(StatusCodes.NotModified) {
                    complete(HttpEntity.Empty)
                  }
                case etag =>
                  respondWithHeader(ETag(EntityTag(stateId))) {
                    complete(images)
                  }
              }
          }
        } ~
        post {
          entity(as[NewImageRequest]) { nir =>
            signatureCheck {
              val addReq = AddImage(nir.displayName, nir.repository, nir.tag)
              onSuccess(imageMonitor ? addReq) {
                case AddedImage(image) =>
                  overrideStatusCode(StatusCodes.Created) {
                    complete(image)
                  }
                case ConflictingImages(images) =>
                  overrideStatusCode(StatusCodes.Conflict) {
                    complete(images)
                  }
              }
            }
          }
        }
      } ~
      pathPrefix("[a-f0-9]+".r) { (id: String) =>
        pathEnd {
          get {
            withImage(id) { image =>
              complete(image)
            }
          } ~
          delete {
            signatureCheck {
              onSuccess(imageMonitor ? RemoveImage(id)) {
                case _: RemovedImage => complete(StatusCodes.NoContent)
                case _: UnknownImage => complete(StatusCodes.NotFound)
              }
            }
          }
        } ~
        path("pull") {
          post {
            signatureCheck {
              withImage(id) { image =>
                onSuccess(imageMonitor ? PullImage(id)) {
                  case _: PullingImage => complete(StatusCodes.Accepted)
                  case _: UnknownImage => complete(StatusCodes.NotFound)
                }
              }
            }
          }
        }
      }
    }
}

object ApiService {

  case class NewContainerRequest(val name: String, val image: String)

  case class NewImageRequest(
    val displayName: String,
    val repository: String,
    val tag: String)

  def apply(
        client: DockerClient,
        imageMonitor: ActorRef,
        signatureActor: Option[ActorRef]
      )(implicit actorRefFactory: ActorRefFactory) =
    new ApiService(actorRefFactory, client, imageMonitor, signatureActor)

  object marshallers extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val newContainerRequestReader = jsonFormat2(NewContainerRequest)
    implicit val newImageRequestReader = jsonFormat3(NewImageRequest)

    implicit val containerWriter = new RootJsonWriter[DockerContainer] {
      def write(c: DockerContainer) =
        JsObject(
          "name" -> JsString(c.name),
          "active" -> JsBoolean(c.isRunning)
        )
    }

    implicit val calendarWriter = new RootJsonWriter[Calendar] {
      def write(c: Calendar) = {
        val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
        df.setTimeZone(c.getTimeZone)
        JsString(df.format(c.getTime))
      }
    }

    implicit val imageWriter = new RootJsonWriter[Image] {
      def write(i: Image) =
        JsObject(
          Map(
            "id" -> JsString(i.id),
            "displayName" -> JsString(i.displayName),
            "repository" -> JsString(i.repository),
            "tag" -> JsString(i.tag)
          ) ++ (i.metadata match {
            case Some(metadata) => Map("metadata" -> JsObject(
              "id" -> JsString(metadata.id),
              "created" -> calendarWriter.write(metadata.created)
            ))
            case None => Map.empty
          })
        )
    }

    implicit val containersWriter = new RootJsonWriter[Seq[DockerContainer]] {
      def write(cs: Seq[DockerContainer]) =
        JsArray(cs.map(containerWriter.write(_)).toSeq: _*)
    }

    implicit val imagesWriter = new RootJsonWriter[Seq[Image]] {
      def write(cs: Seq[Image]) =
        JsArray(cs.map(imageWriter.write(_)).toSeq: _*)
    }

    def fromRequestUnmarshaller[T](feu: FromEntityUnmarshaller[T]): FromRequestUnmarshaller[T] =
      new Unmarshaller[HttpRequest, T] {
        override def apply(value: HttpRequest)(implicit ec: ExecutionContext) =
          feu(value.entity)
      }

    implicit val containerJsonMarshaller: ToResponseMarshaller[DockerContainer] =
      sprayJsonMarshaller(containerWriter)

    implicit val containersJsonMarshaller: ToResponseMarshaller[Seq[DockerContainer]] =
      sprayJsonMarshaller(containersWriter)

    implicit val imageJsonMarshaller: ToResponseMarshaller[Image] =
      sprayJsonMarshaller(imageWriter)

    implicit val imagesJsonMarshaller: ToResponseMarshaller[Seq[Image]] =
      sprayJsonMarshaller(imagesWriter)

  }

}
