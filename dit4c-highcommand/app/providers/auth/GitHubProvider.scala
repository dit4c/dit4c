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
import play.api.mvc.Results
import play.api.Play
import play.api.libs.concurrent.Execution
import play.api.libs.ws.WSResponse

class GitHubProvider(config: GitHubProvider.Config) extends AuthProvider {

  import play.api.libs.functional.syntax._
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

  override val loginHandler = { request: Request[AnyContent] =>
    Future.successful {
      Results.Redirect("https://github.com/login/oauth/authorize", Map(
        "client_id" -> Seq(config.id),
        "scope" -> Seq("user,email")
      ))
    }
  }

  protected def resolveIdentity(code: String): Future[Identity] =
    tokenRequest(code).flatMap(retreiveIdentityWithEmail)

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

  protected def retreiveIdentityWithEmail(token: String) =
    for {
      identity <- retrieveIdentity(token)
      emails <- retrieveEmails(token)
    } yield {
      if (identity.emailAddress.isDefined)
        identity
      else
        identity.copy(emailAddress = emails.headOption)
    }


  protected def retrieveEmails(token: String): Future[Seq[String]] =
    WS.url("https://api.github.com/user/emails")
      .withHeaders(
          "Authorization" -> s"token $token",
          "Accept" -> "application/json")
      .get
      .map { response =>
        throwIfError(response)
        val json = response.json
        val emails = json.as[List[GitHubEmail]]
        // Priority: verified, preferably primary
        emails.sortBy(e => (!e.verified, !e.primary, e.email))
              .map(_.email)
      }

  protected def retrieveIdentity(token: String): Future[GitHubIdentity] =
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

  case class GitHubEmail(
      val email: String,
      val primary: Boolean,
      val verified: Boolean)

  implicit private val githubEmailFormat: Reads[GitHubEmail] = (
      (__ \ "email").read[String] and
      (__ \ "primary").read[Boolean] and
      (__ \ "verified").read[Boolean]
  )(GitHubEmail)


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
