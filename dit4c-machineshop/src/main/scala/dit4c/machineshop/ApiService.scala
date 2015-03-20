package dit4c.machineshop

import akka.actor._
import akka.pattern.ask
import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerContainer
import dit4c.machineshop.images._
import scala.util._
import spray.httpx.marshalling.Marshaller
import spray.httpx.marshalling.ToResponseMarshaller
import spray.httpx.unmarshalling.Unmarshaller
import spray.httpx.unmarshalling.FromRequestUnmarshaller
import spray.httpx.SprayJsonSupport
import spray.httpx.unmarshalling.UnmarshallerLifting
import scala.concurrent.Future
import akka.util.Timeout
import shapeless.HNil
import java.util.Calendar
import java.text.SimpleDateFormat

class ApiService(
    arf: ActorRefFactory,
    client: DockerClient,
    imageMonitor: ActorRef,
    signatureActor: Option[ActorRef]) extends HttpService with RouteProvider {
  import scala.concurrent.duration._
  import dit4c.machineshop.images.ImageManagementActor._

  implicit val timeout = Timeout(10.seconds)

  import dit4c.machineshop.auth.SignatureActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  import ApiService.NewContainerRequest
  import ApiService.NewImageRequest
  import ApiService.marshallers._

  implicit val actorRefFactory = arf

  def withContainer(name: String)(f: DockerContainer => RequestContext => Unit) =
    onSuccess(client.containers.list) { containers =>
      containers.find(c => c.name == name) match {
        case Some(c) => f(c)
        case None => complete(StatusCodes.NotFound)
      }
    }

  def withImage(id: String)(f: Image => RequestContext => Unit) =
    onSuccess(imageMonitor ? ListImages()) {
      case ImageList(images, _) => images.find(_.id == id) match {
        case Some(c) => f(c)
        case None => complete(StatusCodes.NotFound)
      }
    }

  def signatureCheck: Directive0 =
    signatureActor
      .map { actor =>
        headerValueByName("Authorization").flatMap { _ =>
          requestInstance.flatMap { request =>
            val sigCheck: Future[AuthResponse] =
              (actor ask AuthCheck(request)).map(_.asInstanceOf[AuthResponse])
            onSuccess(sigCheck).flatMap[HNil] {
              case AccessGranted =>
                pass
              case AccessDenied(msg) =>
                val challengeHeaders = Nil
                complete(StatusCodes.Forbidden, challengeHeaders, msg)
            }
          }
        } |
        provide("Authorization required using HTTP Signature.").flatMap[HNil] { msg =>
          val challengeHeaders = HttpHeaders.`WWW-Authenticate`(
              HttpChallenge("Signature", "",
                  Map("headers" -> "(request-target) date"))) :: Nil
          complete(StatusCodes.Unauthorized, challengeHeaders, msg)
        }
      }
      .getOrElse( pass )

  val route: RequestContext => Unit =
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
                respondWithStatus(StatusCodes.Created) {
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
                    respondWithStatus(StatusCodes.InternalServerError) {
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
        } ~
        path("export") {
          get {
            signatureCheck {
              withContainer(name) { container =>
                onSuccess(getStreamer) { streamer =>
                  // container.export will pass message chunks directly through
                  (ctx) => container.export(streamer(ctx.responder))
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
              optionalHeaderValueByType[HttpHeaders.`If-None-Match`]() {
                case Some(HttpHeaders.`If-None-Match`(EntityTagRange.Default(tags)))
                    if tags.contains(etag) =>
                  complete(StatusCodes.NotModified)
                case etag =>
                  respondWithHeader(HttpHeaders.ETag(EntityTag(stateId))) {
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
                  respondWithStatus(StatusCodes.Created) {
                    complete(image)
                  }
                case ConflictingImages(images) =>
                  respondWithStatus(StatusCodes.Conflict) {
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

    def getStreamer: Future[ActorRef => HttpMessagePart => Unit] = {
      val ready = scala.concurrent.Promise[ActorRef]()
      actorRefFactory.actorOf {
        Props {
          new Actor with ActorLogging {
            import spray.can.Http

            var responder: ActorRef = null

            override def preStart = {
              log.info("Started streaming response")
              ready.success(self)
            }

            def receive = clearToSend

            val clearToSend: Receive = {
              case r: ActorRef =>
                responder = r
              case "ok" =>
                // Nothing to do while waiting for the next packet
              case part: HttpMessagePart =>
                responder ! part.withAck("ok")
                context.become(waitingForAck(Seq.empty))
              case ev: Http.ConnectionClosed =>
                log.warning("Stopping response streaming due to {}", ev)
                context.stop(self)
            }

            def waitingForAck(queue: Seq[HttpMessagePart]): Receive = {
              case "ok" =>
                queue match {
                  case Nil =>
                    context.become(clearToSend)
                  case head :: Nil if head.isInstanceOf[ChunkedMessageEnd] =>
                    responder ! queue.head
                    context.stop(self)
                  case head :: tail =>
                    responder ! head.withAck("ok")
                    context.become(waitingForAck(tail))
                }
              case part: HttpMessagePart =>
                context.become(waitingForAck(queue :+ part))
              case ev: Http.ConnectionClosed =>
                log.warning("Stopping response streaming due to {}", ev)
                context.stop(self)
            }
          }
        }
      }
      ready.future.map { sender =>
        (responder: ActorRef) => {
          sender ! responder
          (part: HttpMessagePart) => sender ! part
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

  object marshallers extends DefaultJsonProtocol with SprayJsonSupport with UnmarshallerLifting {
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

    implicit val newContainerRequestUnmarshaller: FromRequestUnmarshaller[NewContainerRequest] =
      fromRequestUnmarshaller(fromMessageUnmarshaller(sprayJsonUnmarshaller(newContainerRequestReader)))

    implicit val newImageRequestUnmarshaller: FromRequestUnmarshaller[NewImageRequest] =
      fromRequestUnmarshaller(fromMessageUnmarshaller(sprayJsonUnmarshaller(newImageRequestReader)))

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
