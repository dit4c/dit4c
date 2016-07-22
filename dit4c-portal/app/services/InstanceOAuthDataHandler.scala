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

class InstanceOAuthDataHandler(
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager
    )(implicit ec: ExecutionContext) extends DataHandler[IdentityService.User] {

  import akka.pattern.ask

  /**
   * Validate client_id against instance JWT client_secret
   */
  def validateClient(request: AuthorizationRequest): Future[Boolean] =
    request.clientCredential
      .collect[Future[Boolean]] {
        case cred if cred.clientId.startsWith("instance-") && cred.clientSecret.isDefined =>
          implicit val timeout = Timeout(1.minute)
          (instanceAggregateManager ? InstanceAggregateManager.VerifyJwt(cred.clientSecret.get)).map {
            case InstanceAggregate.ValidJwt(instanceId) =>
              cred.clientId == s"instance-$instanceId"
            case InstanceAggregate.InvalidJwt =>
              false
          }
      }
      .getOrElse(Future.successful(false))

  /**
   * TODO: needs implementing
   */
  def createAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[AccessToken] = {
    import LoginInfo.jsonFormat
    val serializedLoginInfo = Json.stringify(Json.toJson(authInfo.user.loginInfo))
    JwtClaim(subject = Some(serializedLoginInfo))
    ???
  }

  /**
   * Access tokens are state-less, so we have no need to reuse them
   */
  def getStoredAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[Option[AccessToken]] =
    Future.successful(None)

  /**
   * Not intended to be used, but ultimately correct behavior
   */
  def refreshAccessToken(authInfo: AuthInfo[IdentityService.User], refreshToken: String): Future[AccessToken] =
    createAccessToken(authInfo)

  /**
   * TODO: needs implementing
   */
  def findAuthInfoByCode(code: String): Future[Option[AuthInfo[IdentityService.User]]] = ???

  /**
   * Authentication tokens are state-less, so we have no need to reuse them
   */
  def deleteAuthCode(code: String): Future[Unit] = ???

  /**
   * Not required for Authorization Code Grant, so not implemented
   */
  def findUser(request: AuthorizationRequest): Future[Option[IdentityService.User]] = Future.successful(None)
  def findAuthInfoByRefreshToken(refreshToken: String): Future[Option[AuthInfo[IdentityService.User]]] = ???
  def findAccessToken(token: String): Future[Option[AccessToken]] = ???
  def findAuthInfoByAccessToken(accessToken: AccessToken): Future[Option[AuthInfo[IdentityService.User]]] = ???
}