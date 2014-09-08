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
import play.api.libs.concurrent.Execution
import play.api.libs.ws.WSResponse

class GitHubProvider(config: GitHubProvider.Config) extends AuthProvider {

  import Play.current
  import Execution.Implicits.defaultContext

  override def name = "github"

  type Callback = Request[AnyContent] => Future[CallbackResult]

  override val callbackHandler: Callback = { request: Request[AnyContent] =>
    request.getQueryString("code") match {
      case Some(code) =>
        resolveIdentity(code)
          .map[CallbackResult](CallbackResult.Success.apply)
          .recover[CallbackResult] {
            case e => CallbackResult.Failure(e.getMessage)
          }
      case None =>
        Future.successful(CallbackResult.Invalid)
    }
  }

  override val loginURL =
    s"https://github.com/login/oauth/authorize?client_id=${config.id}"

  override val loginButton = (url: String) => Html(
    s"""|<a target="_self" href="$url">
        |  <img class="img-responsive center-block" alt="Login with GitHub"
        |       src="https://octodex.github.com/images/original.png"/>
        |</a>
        |""".stripMargin
  )

  protected def resolveIdentity(code: String): Future[Identity] =
    tokenRequest(code).flatMap(retrieveIdentity)

  protected def tokenRequest(code: String): Future[String] =
    WS.url("https://github.com/login/oauth/access_token")
      .withHeaders("Accept" -> "application/json")
      .post(Map(
          "code" -> Seq(code),
          "client_id" -> Seq(config.id),
          "client_secret" -> Seq(config.secret)))
      .map { response =>
        throwIfError(response)
        (response.json \ "access_token").as[String]
      }

  protected def retrieveIdentity(token: String): Future[Identity] =
    WS.url("https://api.github.com/user")
      .withHeaders(
          "Authorization" -> s"token $token",
          "Accept" -> "application/json")
      .get
      .map { response =>
        throwIfError(response)
        val json = response.json
        GitHubIdentity(
          (json \ "login").as[String],
          (json \ "name").as[Option[String]],
          (json \ "email").as[Option[String]]
        )
      }

  protected def throwIfError(response: WSResponse): Unit =
    (response.json \ "error_description").as[Option[String]]
      .foreach(msg => throw new Exception(msg))


  case class GitHubIdentity(
      username: String,
      name: Option[String],
      emailAddress: Option[String]) extends Identity {

    def uniqueId = s"github:$username"

  }


}

object GitHubProvider extends AuthProviderFactory {

  case class Config(id: String, secret: String)

  def apply(config: play.api.Configuration) =
    for {
      c <- config.getConfig("github")
      id <- c.getString("id")
      secret <- c.getString("secret")
    } yield new GitHubProvider(
        new GitHubProvider.Config(id, secret))

}