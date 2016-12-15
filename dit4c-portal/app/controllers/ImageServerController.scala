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
import play.api.http.HttpVerbs
import play.api.mvc.RequestHeader

class ImageServerController(
    val instanceSharder: ActorRef @@ InstanceSharder.type)(
        implicit system: ActorSystem, executionContext: ExecutionContext)
    extends Controller {

  import akka.pattern.ask

  val log = play.api.Logger(this.getClass)

  def authHttpRequest(httpVerb: String, bucketId: String) = Action.async { implicit request =>
    import HttpVerbs._
    httpVerb match {
      case DELETE  => authHttpRequestWrite(bucketId)(request)
      case GET     => alwaysOk(request)
      case HEAD    => alwaysOk(request)
      case OPTIONS => alwaysOk(request)
      case PATCH   => authHttpRequestWrite(bucketId)(request)
      case POST    => authHttpRequestWrite(bucketId)(request)
      case PUT     => authHttpRequestWrite(bucketId)(request)
    }
  }

  def alwaysOk = Action { Ok }

  def authHttpRequestWrite(bucketId: String) = Action.async { implicit request =>
    (bucketId, request) match {
      case (InstanceBucket(instanceId), AuthJwt(token)) =>
        authInstanceBucketWrite(instanceId, token)(request)
      case v =>
        log.warn(s"Unhandled image server auth: $v")
        Future.successful(Forbidden)
    }
  }

  protected def authInstanceBucketWrite(instanceId: String, token: String) = Action.async { implicit request =>
    InstanceSharder.resolveJwtInstanceId(token) match {
      case Left(msg) =>
        log.error(token)
        Future.successful(Forbidden(msg))
      case Right(instanceId) =>
        implicit val timeout = Timeout(1.minute)
        (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token))).map {
          case InstanceAggregate.ValidJwt(instanceId) =>
            Ok
          case InstanceAggregate.InvalidJwt(msg) =>
            log.warn(s"Invalid JWT: $msg\n${request.body}")
            Forbidden(msg)
        }
    }
  }


  private object InstanceBucket {
    def unapply(bucketId: String): Option[String] =
      Some(bucketId.split("-").toList).collect {
        case "instance" :: instanceId :: Nil =>
          instanceId
      }
  }

  private object AuthJwt {
    def unapply(r: RequestHeader): Option[String] = {
      val authHeaderRegex = "^Bearer (.*)$".r
      r.headers.get("Authorization")
        .collect { case authHeaderRegex(token) => token }
    }
  }

}
