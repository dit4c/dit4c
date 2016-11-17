package services

import scalaoauth2.provider.DataHandler
import scala.concurrent.Future
import scalaoauth2.provider.AuthInfo
import scalaoauth2.provider.AccessToken
import scalaoauth2.provider.AuthorizationRequest
import com.softwaremill.tagging._
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import scala.concurrent.ExecutionContext
import com.mohiva.play.silhouette.api.LoginInfo
import play.api.libs.json.Json
import pdi.jwt.JwtClaim
import utils.oauth.AuthorizationCodeGenerator
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.util.Random
import java.time.Instant
import java.util.Date
import play.api.Logger

object InstanceOAuthDataHandler {
  implicit def authInfoFormat[U](implicit ru: Reads[U], wu: Writes[U]): Format[AuthInfo[U]] = (
    (__ \ 'user).format[U] and
    (__ \ 'clientId).formatNullable[String] and
    (__ \ 'scope).formatNullable[String] and
    (__ \ 'redirectUri).formatNullable[String]
  )(AuthInfo.apply _, unlift(AuthInfo.unapply))
}

class InstanceOAuthDataHandler(
    val authorizationCodeGenerator: AuthorizationCodeGenerator,
    val instanceSharder: ActorRef @@ InstanceSharder.type,
    val identityService: IdentityService
    )(implicit ec: ExecutionContext) extends DataHandler[IdentityService.User] {

  val log = Logger(this.getClass)

  import akka.pattern.ask
  import InstanceOAuthDataHandler._
  import LoginInfo.jsonFormat

  /**
   * Validate client_id against instance JWT client_secret
   */
  override def validateClient(request: AuthorizationRequest): Future[Boolean] =
    request.clientCredential
      .collect[Future[Boolean]] {
        case cred if cred.clientId.startsWith("instance-") && cred.clientSecret.isDefined =>
          implicit val timeout = Timeout(1.minute)
          val token = cred.clientSecret.get
          InstanceSharder.resolveJwtInstanceId(token) match {
            case Left(msg) =>
              log.error(msg)
              Future.successful(false)
            case Right(instanceId) =>
              (instanceSharder ? InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token))).map {
                case InstanceAggregate.ValidJwt(instanceId) =>
                  cred.clientId == s"instance-$instanceId"
                case InstanceAggregate.InvalidJwt =>
                  false
              }
          }
      }
      .getOrElse(Future.successful(false))

  /**
   * Not part of DataHandler, but here for completeness
   */
  def createAuthCode(authInfo: AuthInfo[IdentityService.User]): Future[String] = Future.successful {
    authorizationCodeGenerator.create(
      AuthInfo(authInfo.user.loginInfo, authInfo.clientId, authInfo.scope, authInfo.redirectUri))
  }

  /**
   * TODO: needs implementing
   */
  override def createAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[AccessToken] = Future.successful {
    AccessToken(Random.alphanumeric.take(20).mkString, None, authInfo.scope, None, Date.from(Instant.now))
  }

  /**
   * Access tokens are state-less, so we have no need to reuse them
   */
  override def getStoredAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[Option[AccessToken]] =
    Future.successful(None)

  /**
   * Not intended to be used, but ultimately correct behavior
   */
  override def refreshAccessToken(authInfo: AuthInfo[IdentityService.User], refreshToken: String): Future[AccessToken] =
    createAccessToken(authInfo)

  /**
   * Reads info encoded previously by createAuthCode()
   */
  override def findAuthInfoByCode(code: String): Future[Option[AuthInfo[IdentityService.User]]] =
      for {
        // Get AutInfo[LoginData] from code
        info <- Future.fromTry(authorizationCodeGenerator.decode[AuthInfo[LoginInfo]](code))
        // Resolve it against identity service
        user <- identityService.retrieve(info.user)
      } yield user.map(AuthInfo(_, info.clientId, info.scope, info.redirectUri))

  /**
   * Authentication tokens are state-less, so we have no way to delete them
   */
  override def deleteAuthCode(code: String): Future[Unit] = Future.successful(())

  /**
   * Not required for Authorization Code Grant, so not implemented
   */
  override def findUser(request: AuthorizationRequest): Future[Option[IdentityService.User]] = Future.successful(None)
  override def findAuthInfoByRefreshToken(refreshToken: String): Future[Option[AuthInfo[IdentityService.User]]] = ???
  override def findAccessToken(token: String): Future[Option[AccessToken]] = ???
  override def findAuthInfoByAccessToken(accessToken: AccessToken): Future[Option[AuthInfo[IdentityService.User]]] = ???
}