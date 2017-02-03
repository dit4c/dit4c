package controllers

import com.softwaremill.tagging._
import scala.concurrent.duration._
import play.api.mvc.Controller
import akka.util.Timeout
import play.api.mvc.Action
import domain.InstanceAggregate
import services.InstanceSharder
import scala.concurrent._
import akka.actor._

class InstanceController(
    val instanceSharder: ActorRef @@ InstanceSharder.type)(
        implicit system: ActorSystem, executionContext: ExecutionContext)
    extends Controller {

  import akka.pattern.ask

  val log = play.api.Logger(this.getClass)

  def instanceRegistration = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    val authHeaderRegex = "^Bearer (.*)$".r
    request.headers.get("Authorization")
      .collect { case authHeaderRegex(token) => token }
      .map { token =>
        InstanceSharder.resolveJwtInstanceId(token) match {
          case Left(msg) =>
            log.error(token)
            Future.successful(BadRequest(msg))
          case Right(instanceId) =>
            (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token))).flatMap {
              case InstanceAggregate.ValidJwt(instanceId) =>
                log.debug(s"Valid JWT for $instanceId")
                request.body.asText match {
                  case Some(uri) =>
                    (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.AssociateUri(uri))).map { _ =>
                      Ok("")
                    }.recover {
                      case e => InternalServerError(e.getMessage)
                    }
                  case None =>
                    Future.successful(BadRequest("No valid uri"))
                }
              case InstanceAggregate.InvalidJwt(msg) =>
                log.warn(s"Invalid JWT: $msg\n${request.body}")
                Future.successful(BadRequest(msg))
            }
        }
      }
      .getOrElse(Future.successful(Forbidden("No valid JWT provided")))
  }

  def instancePgpKeys(instanceId: String) = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.GetKeyFingerprint)).map {
      case InstanceAggregate.CurrentKeyFingerprint(keyFingerprint) =>
        Redirect(routes.KeyRingController.get(keyFingerprint.string))
      case InstanceAggregate.DoesNotExist =>
        NotFound
    }
  }

  def imageRegistration = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    val authHeaderRegex = "^Bearer (.*)$".r
    request.headers.get("Authorization")
      .collect { case authHeaderRegex(token) => token }
      .map { token =>
        InstanceSharder.resolveJwtInstanceId(token) match {
          case Left(msg) =>
            log.error(token)
            Future.successful(BadRequest(msg))
          case Right(instanceId) =>
            (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token))).flatMap {
              case InstanceAggregate.ValidJwt(instanceId) =>
                log.debug(s"Valid JWT for $instanceId")
                request.body.asText match {
                  case Some(uri) =>
                    (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.AssociateImage(uri))).map { _ =>
                      Ok("")
                    }.recover {
                      case e => InternalServerError(e.getMessage)
                    }
                  case None =>
                    Future.successful(BadRequest("No valid image uri"))
                }
              case InstanceAggregate.InvalidJwt(msg) =>
                log.warn(s"Invalid JWT: $msg\n${request.body}")
                Future.successful(BadRequest(msg))
            }
        }
      }
      .getOrElse(Future.successful(Forbidden("No valid JWT provided")))
  }

}