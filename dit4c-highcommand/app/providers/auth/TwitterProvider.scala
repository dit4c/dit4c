package providers.auth

import utils.jwt.JWSVerifier
import play.api.mvc.Request
import play.api.mvc.AnyContent
import scala.util.Try
import com.nimbusds.jwt.JWTParser
import play.api.libs.json._
import play.twirl.api.Html
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.Play
import play.api.mvc.Results
import play.api.libs.concurrent.Execution
import play.api.libs.ws.WSResponse
import play.api.libs.oauth._
import java.net.URL
import oauth.signpost.exception.OAuthException
import play.api.libs.ws.WSClient

class TwitterProvider(info: ServiceInfo, ws: WSClient) extends AuthProvider {

  import play.api.libs.functional.syntax._
  import Execution.Implicits.defaultContext

  override def name = "twitter"
  protected def sessionTokenKey = s"$name-oauth-rt"

  protected val oauth = OAuth(info, use10a = true)

  implicit protected val rtFormat = Json.format[RequestToken]
  
  override val callbackHandler = { request: Request[AnyContent] =>
    (for {
      verifier <- request.getQueryString("oauth_verifier")
      token <- request.session.get(sessionTokenKey)
        .map(Json.parse)
        .map(_.as[RequestToken])
    } yield {
      Future(oauth.retrieveAccessToken(token, verifier)).flatMap {
        case Right(accessToken: RequestToken) =>
          ws.url("https://api.twitter.com/1.1/account/verify_credentials.json")
            .sign(OAuthCalculator(info.key, accessToken))
            .get
            .map {
            case result if result.status == 200 =>
              val json = result.json
              CallbackResult.Success(TwitterIdentity(
                (json \ "screen_name").as[String],
                (json \ "name").asOpt[String]
              ))
            case result =>
              CallbackResult.Failure(result.body)
          }
        case Left(oauthException: OAuthException) =>
          Future.successful(
              CallbackResult.Failure(oauthException.getMessage))
      }
    }).getOrElse(Future.successful(CallbackResult.Invalid))
  }

  override val loginHandler = { implicit request: Request[AnyContent] =>
    for {
      rt <- Future {
        val callbackUrl =
          controllers.routes.AuthController.namedCallback(name).absoluteURL()
        oauth.retrieveRequestToken(callbackUrl)
      }
    } yield rt match {
      case Right(rt: RequestToken) =>
        Results.Redirect(oauth.redirectUrl(rt.token)).addingToSession(
          sessionTokenKey -> Json.stringify(Json.toJson(rt))
        )
      case Left(oauthException: OAuthException) =>
        Results.InternalServerError(oauthException.toString)
    }
  }

  case class TwitterIdentity(
      handle: String,
      val name: Option[String]) extends Identity {
    override val uniqueId = "twitter:"+handle
    override val emailAddress = None
  }

}

object TwitterProvider extends AuthProviderFactory {

  def apply(
      config: play.api.Configuration,
      ws: WSClient): Iterable[AuthProvider] =
    for {
      baseUrl <- config.getString("application.baseUrl")
      c <- config.getConfig("twitter")
      key <- c.getString("key")
      secret <- c.getString("secret")
      consumerKey = ConsumerKey(key, secret)
      info = ServiceInfo(
        "https://api.twitter.com/oauth/request_token",
        "https://api.twitter.com/oauth/access_token",
        "https://api.twitter.com/oauth/authenticate",
        consumerKey
      )
    } yield new TwitterProvider(info, ws)

}
