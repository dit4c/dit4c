package dit4c.machineshop

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.pattern.ask
import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import scala.collection.JavaConversions._
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerContainer
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

class ApiService(
    arf: ActorRefFactory,
    client: DockerClient,
    signatureActor: Option[ActorRef]) extends HttpService with RouteProvider {
  import scala.concurrent.duration._

  implicit val timeout = Timeout(10.seconds)

  import dit4c.machineshop.auth.SignatureActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  import ApiService.NewContainerRequest
  import ApiService.marshallers._
  import ApiService.marshallers.newContainerRequestUnmarshaller

  implicit val actorRefFactory = arf

  def withContainer(name: String)(f: DockerContainer => RequestContext => Unit) =
    onSuccess(client.containers.list) { containers =>
      containers.find(c => c.name == name) match {
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
        }
      } ~
      path("new") {
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
        }
      }
    }

}

object ApiService {

  case class NewContainerRequest(val name: String, val image: String)

  def apply(
        client: DockerClient,
        signatureActor: Option[ActorRef]
      )(implicit actorRefFactory: ActorRefFactory) =
    new ApiService(actorRefFactory, client, signatureActor)

  object marshallers extends DefaultJsonProtocol with SprayJsonSupport with UnmarshallerLifting {
    implicit val newContainerRequestReader = jsonFormat2(NewContainerRequest)

    implicit val containerWriter = new RootJsonWriter[DockerContainer] {
      def write(c: DockerContainer) = {
        JsObject(
          "name" -> JsString(c.name),
          "active" -> JsBoolean(c.isRunning)
        )
      }
    }

    implicit val containersWriter = new RootJsonWriter[Seq[DockerContainer]] {
      def write(cs: Seq[DockerContainer]) =
        JsArray(cs.map(containerWriter.write(_)).toSeq: _*)
    }

    implicit val newContainerRequestUnmarshaller: FromRequestUnmarshaller[NewContainerRequest] =
      fromRequestUnmarshaller(fromMessageUnmarshaller(sprayJsonUnmarshaller(newContainerRequestReader)))

    implicit val containerJsonMarshaller: ToResponseMarshaller[DockerContainer] =
      sprayJsonMarshaller(containerWriter)

    implicit val containersJsonMarshaller: ToResponseMarshaller[Seq[DockerContainer]] =
      sprayJsonMarshaller(containersWriter)

  }

}