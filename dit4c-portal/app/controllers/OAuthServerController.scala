package controllers

import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.i18n._
import com.softwaremill.tagging._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import akka.actor._
import services._
import domain._
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.StatusCodes.ServerError
import akka.http.scaladsl.model.Uri
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.Silhouette
import utils.auth.DefaultEnv
import com.mohiva.play.silhouette.api.LoginEvent
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import com.mohiva.play.silhouette.impl.providers.SocialProvider
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfileBuilder
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import play.api.Environment
import play.api.Mode
import scalaoauth2.provider._

class OAuthServerController(
    val silhouette: Silhouette[DefaultEnv],
    val oauthDataHandler: InstanceOAuthDataHandler)(implicit ec: ExecutionContext)
    extends Controller
    with OAuth2Provider {

  val log = play.Logger.underlying


  override val tokenEndpoint = new TokenEndpoint {
    override val handlers = Map(
      OAuthGrantType.AUTHORIZATION_CODE -> new AuthorizationCode()
    )
  }

  def authorize(clientId: String, redirectUri: String) = silhouette.UserAwareAction.async { request =>
    request.identity match {
      case Some(user: IdentityService.User) =>
        val authInfo = AuthInfo.apply(user, Some(clientId), None, Some(redirectUri))
        for {
          code <- oauthDataHandler.createAuthCode(authInfo)
        } yield {
          Redirect(redirectUri, Map("code" -> Seq(code)), FOUND)
        }
      case None =>
        Future.successful {
          Redirect(routes.MainController.index)
        }
    }
  }

  def accessToken = silhouette.UnsecuredAction.async { implicit request =>
    issueAccessToken(oauthDataHandler)
  }

}
