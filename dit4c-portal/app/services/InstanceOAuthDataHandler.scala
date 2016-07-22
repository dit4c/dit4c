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

class InstanceOAuthDataHandler(
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager
    )(implicit ec: ExecutionContext) extends DataHandler[IdentityService.User] {

  import akka.pattern.ask

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

  def createAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[AccessToken] = ???
  def deleteAuthCode(code: String): Future[Unit] = ???
  def findAuthInfoByCode(code: String): Future[Option[AuthInfo[IdentityService.User]]] = ???
  def findAuthInfoByRefreshToken(refreshToken: String): Future[Option[AuthInfo[IdentityService.User]]] = ???
  def findUser(request: scalaoauth2.provider.AuthorizationRequest): Future[Option[IdentityService.User]] = ???
  def getStoredAccessToken(authInfo: AuthInfo[IdentityService.User]): Future[Option[AccessToken]] = ???
  def refreshAccessToken(authInfo: AuthInfo[IdentityService.User],refreshToken: String): Future[AccessToken] = ???

  // Members declared in scalaoauth2.provider.ProtectedResourceHandler
  def findAccessToken(token: String): Future[Option[AccessToken]] = ???
  def findAuthInfoByAccessToken(accessToken: AccessToken): Future[Option[AuthInfo[IdentityService.User]]] = ???
}