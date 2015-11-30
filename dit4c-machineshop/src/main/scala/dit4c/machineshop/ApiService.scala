package dit4c.machineshop

import java.text.SimpleDateFormat
import java.util.Calendar
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util._
import akka.actor._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.marshalling.PredefinedToResponseMarshallers._
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.unmarshalling._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.stage.Context
import akka.stream.stage.PushPullStage
import akka.util.ByteString
import akka.util.Timeout
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerContainer
import spray.json._
import akka.http.scaladsl.model.HttpResponse

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
    fromRequestUnmarshaller(sprayJsonUnmarshaller(newContainerRequestReader))

  implicit val newImageRequestUnmarshaller: FromRequestUnmarshaller[NewImageRequest] =
    fromRequestUnmarshaller(sprayJsonUnmarshaller(newImageRequestReader))

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
                complete((StatusCodes.Forbidden, msg))
            }
          }
        } ~
        provide("Authorization required using HTTP Signature.") { msg =>
          val challengeHeaders = `WWW-Authenticate`(
              HttpChallenge("Signature", "",
                  Map("headers" -> "(request-target) date"))) :: Nil
          complete((StatusCodes.Unauthorized, challengeHeaders, msg))
        }
      }
      .getOrElse( f )

  val route: RequestContext => Future[RouteResult] =
    pathPrefix("containers") {
      pathEndOrSingleSlash {
        get {
          onSuccess(client.containers.list) { containers =>
            complete(containers)
          }
        } ~
        post {
          entity(as[NewContainerRequest]) { npr =>
            signatureCheck {
              onSuccess(client.containers.create(npr.name, npr.image)) { container =>
                implicit val m = fromToEntityMarshaller(StatusCodes.Created)
                complete(container)
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
                    complete((StatusCodes.InternalServerError, e.getMessage))
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
        } ~
        path("export") {
          get {
            signatureCheck {
              withContainer(name) { container =>
                onSuccess(container.export) { byteSource =>
                  val contentType = ContentType(MediaTypes.`application/x-tar`)
                  val content =
                    byteSource.transform(() => new ApiService.ChunkingStage)
                  complete(HttpEntity.Chunked(contentType, content))
                }
              }
            }
          }
        }
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
                  complete((StatusCodes.NotModified, HttpEntity.Empty))
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
                  implicit val m = fromToEntityMarshaller(StatusCodes.Created)
                  complete(image)
                case ConflictingImages(images) =>
                  implicit val m = fromToEntityMarshaller(StatusCodes.Conflict)
                  complete(images)
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
        override def apply(value: HttpRequest)(
            implicit ec: ExecutionContext, mat: akka.stream.Materializer) =
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

  class ChunkingStage extends PushPullStage[ByteString,ChunkStreamPart]() {

    override def onPush(elem: ByteString, ctx: Context[ChunkStreamPart]) =
      ctx.push(HttpEntity.Chunk(elem.toArray))

    override def onPull(ctx: Context[ChunkStreamPart]) =
      if (!ctx.isFinishing) ctx.pull
      else ctx.pushAndFinish(HttpEntity.LastChunk)

    override def onUpstreamFinish(ctx: Context[ChunkStreamPart]) =
      ctx.absorbTermination

  }

}
