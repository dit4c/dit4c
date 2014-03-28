package dit4c.gatehouse

import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._
import akka.actor.ActorRef
import akka.pattern.ask
import dit4c.gatehouse.docker.DockerIndexActor._
import akka.util.Timeout
import spray.util.pimpFuture
import akka.actor.ActorSystem
import akka.actor.ActorRefFactory
import spray.http.HttpHeaders.RawHeader
import akka.event.Logging
import scala.util.{Success, Failure}
import akka.event.LoggingReceive
import dit4c.gatehouse.auth.AuthorizationChecker

class AuthService(val actorRefFactory: ActorRefFactory, dockerIndex: ActorRef) extends HttpService {
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  implicit val timeout = Timeout(100.millis)

  val AUTH_TOKEN_COOKIE = "dit4c-jwt"

  val authorizationChecker = new AuthorizationChecker

  val route =
    logRequestResponse("") {
    path("auth") {
      get {
        host("""^(\w+)\.""".r) { containerName =>
          optionalCookie(AUTH_TOKEN_COOKIE) {
            case Some(jwtCookie) if authorizationChecker(containerName)(jwtCookie.content) =>
              onComplete(dockerIndex ask PortQuery(containerName)) {
                case Success(PortReply(Some(port))) =>
                  respondWithHeader(RawHeader("X-Upstream-Port", s"$port")) {
                    complete(200, HttpEntity.Empty)
                  }
                case Success(PortReply(None)) =>
                  complete(404, HttpEntity.Empty)
                case Failure(e)  =>
                  logRequestResponse("query error") {
                    complete(500, HttpEntity.Empty)
                  }
              }
            case _ =>
              // Missing or invalid authorization cookie
              complete(403, HttpEntity.Empty)
          }
        } ~
        respondWithStatus(400) {
          complete("A Host header with at least two DNS labels is required.")
        }
      }
    }
  }
}

object AuthService {

  def apply(actorRefFactory: ActorRefFactory, dockerIndex: ActorRef) =
    new AuthService(actorRefFactory, dockerIndex)

}