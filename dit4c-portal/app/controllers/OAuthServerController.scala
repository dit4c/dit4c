package controllers

import scala.concurrent._
import scala.concurrent.duration._

import com.mohiva.play.silhouette.api.Silhouette
import com.softwaremill.tagging._

import akka.actor.ActorRef
import play.api.mvc.Controller
import scalaoauth2.provider._
import services._
import utils.auth.DefaultEnv
import services.UserAggregateManager.UserEnvelope
import domain.UserAggregate.{ GetAllInstanceIds, UserInstances }
import akka.util.Timeout

class OAuthServerController(
    val silhouette: Silhouette[DefaultEnv],
    val userAggregateManager: ActorRef @@ UserAggregateManager,
    val oauthDataHandler: InstanceOAuthDataHandler)(implicit ec: ExecutionContext)
    extends Controller
    with OAuth2Provider {

  import akka.pattern.ask
  implicit val timeout = Timeout(30.seconds)

  val log = play.Logger.underlying

  override val tokenEndpoint = new TokenEndpoint {
    override val handlers = Map(
      OAuthGrantType.AUTHORIZATION_CODE -> new AuthorizationCode()
    )
  }

  def authorize(clientId: String, redirectUri: String) = silhouette.UserAwareAction.async { request =>
    request.identity match {
      case Some(user: IdentityService.User) if clientId.startsWith("instance-")=>
        val instanceId = clientId.stripPrefix("instance-")
        checkUserOwnsInstance(user.id, instanceId).flatMap {
          case true =>
            val authInfo = AuthInfo.apply(user, Some(clientId), None, Some(redirectUri))
            oauthDataHandler.createAuthCode(authInfo).map { code =>
              Redirect(redirectUri, Map("code" -> Seq(code)), FOUND)
            }
          case false =>
            Future.successful {
              Forbidden("You attempted to access an instance you do not own")
            }
        }
      case None =>
        Future.successful {
          Redirect(routes.MainController.index)
            .withSession("redirect_uri" -> redirectUri)
        }
    }
  }

  def accessToken = silhouette.UnsecuredAction.async { implicit request =>
    issueAccessToken(oauthDataHandler)
  }

  private def checkUserOwnsInstance(userId: String, instanceId: String): Future[Boolean] =
    (userAggregateManager ? UserEnvelope(userId, GetAllInstanceIds)).collect {
      case UserInstances(instanceIds) =>
        instanceIds.contains(instanceId)
    }

}
