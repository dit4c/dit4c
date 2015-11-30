package dit4c.gatehouse

import spray.json._
import akka.http.scaladsl.model.MediaTypes._
import akka.actor.ActorRef
import akka.pattern.ask
import dit4c.gatehouse.auth.AuthActor._
import dit4c.gatehouse.docker.DockerIndexActor._
import akka.util.Timeout
import akka.actor.ActorSystem
import akka.actor.ActorRefFactory
import akka.event.Logging
import scala.util.{Success, Failure}
import akka.event.LoggingReceive
import dit4c.gatehouse.auth.AuthorizationChecker
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RequestContext
import scala.concurrent.Future
import akka.http.scaladsl.server.RouteResult

class AuthService(val actorRefFactory: ActorRefFactory, dockerIndex: ActorRef, auth: ActorRef) {
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  implicit val timeout = Timeout(750.millis)

  val AUTH_TOKEN_COOKIE = "dit4c-jwt"

  val authorizationChecker = new AuthorizationChecker

  val route: RequestContext => Future[RouteResult] =
    path("auth") {
      get {
        headerValueByName("X-Server-Name") { containerName =>
          optionalCookie(AUTH_TOKEN_COOKIE) {
            case Some(jwtCookie) =>
              onComplete(auth ask AuthCheck(jwtCookie.value, containerName)) {
                case Success(AccessGranted) =>
                  onComplete(dockerIndex ask PortQuery(containerName)) {
                    case Success(PortReply(Some(port))) =>
                      respondWithHeader(RawHeader("X-Upstream-Port", s"$port")) {
                        complete(HttpResponse(200))
                      }
                    case Success(PortReply(None)) =>
                      complete(HttpResponse(404))
                    case Success(obj) =>
                      logRequestResult(s"query error - unknown reply: $obj") {
                        complete(HttpResponse(500))
                      }
                    case Failure(e)  =>
                      logRequestResult(s"query error: $e") {
                        complete(HttpResponse(500))
                      }
                  }
                case Success(AccessDenied(reason)) =>
                  logRequestResult(reason, Logging.InfoLevel) {
                    complete(403, reason)
                  }
                case Success(unknown) =>
                  logRequestResult(s"unknown auth response: $unknown", Logging.InfoLevel) {
                    complete(HttpResponse(500))
                  }
                case Failure(e) =>
                  logRequestResult(s"query error: $e", Logging.InfoLevel) {
                    complete(HttpResponse(500))
                  }
              }
            case _ =>
              // Missing cookie
              complete(HttpResponse(403))
          }
        } ~ complete((400, "A X-Server-Name header is required."))
      }
    }
}

object AuthService {

  def apply(actorRefFactory: ActorRefFactory, dockerIndex: ActorRef, auth: ActorRef) =
    new AuthService(actorRefFactory, dockerIndex, auth)

}
