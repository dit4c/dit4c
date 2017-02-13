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
import akka.http.scaladsl.model.Uri
import java.util.Base64

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
      case GET     => authHttpRequestRead(bucketId)(request)
      case HEAD    => authHttpRequestRead(bucketId)(request)
      case OPTIONS => alwaysOk(request)
      case PATCH   => authHttpRequestWrite(bucketId)(request)
      case POST    => authHttpRequestWrite(bucketId)(request)
      case PUT     => authHttpRequestWrite(bucketId)(request)
    }
  }

  protected def alwaysOk = Action { Ok }

  protected def authHttpRequestRead(bucketId: String) = Action.async { implicit request =>
    (bucketId, request) match {
      case (InstanceBucket(instanceId), AuthBasic(user, password)) =>
        authInstanceBucketRead(instanceId, user, password)(request)
      case (InstanceBucket(instanceId), NoAuthCredentials()) =>
        Future.successful(Unauthorized.withHeaders("WWW-Authenticate" -> s"""Basic realm="$bucketId""""))
      case v =>
        log.warn(s"Unhandled image server auth: $v")
        Future.successful(Forbidden)
    }
  }

  protected def authHttpRequestWrite(bucketId: String) = Action.async { implicit request =>
    (bucketId, request) match {
      case (InstanceBucket(instanceId), AuthJwt(token)) =>
        authInstanceBucketWrite(instanceId, token)(request)
      case v =>
        log.warn(s"Unhandled image server auth: $v")
        Future.successful(Forbidden)
    }
  }

  protected def authInstanceBucketRead(instanceId: String, user: String, password: String) = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.GetImageUrl(user))).map {
      case InstanceAggregate.InstanceImage(url) =>
        val List(expectedUser, expectedPassword) = Uri(url).authority.userinfo.split(":", 2).toList
        if (expectedPassword == password) {
          log.info(s"Granting instance image $instanceId access to $user")
          Ok
        } else {
          log.warn(s"Forbid access to instance image $instanceId: incorrect password")
          Forbidden
        }
      case InstanceAggregate.NoImageExists =>
        Forbidden
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

  private object NoAuthCredentials {
    def unapply(r: RequestHeader): Boolean = !r.headers.keys.contains("Authorization")
  }

  private object AuthJwt {
    def unapply(r: RequestHeader): Option[String] = {
      val authHeaderRegex = "^Bearer (.*)$".r
      r.headers.get("Authorization")
        .collect { case authHeaderRegex(token) => token }
    }
  }

  private object AuthBasic {
    def unapply(r: RequestHeader): Option[(String, String)] = {
      val authHeaderRegex = "^Basic (.*)$".r
      val authBasicRegex = "^(.*):(.*)$".r
      r.headers.get("Authorization")
        .collect { case authHeaderRegex(data) => data }
        .map(Base64.getDecoder.decode)
        .map(new String(_, "utf-8"))
        .collect { case authBasicRegex(user, password) => (user, password) }
    }
  }

}
