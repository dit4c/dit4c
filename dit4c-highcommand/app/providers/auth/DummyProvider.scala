package providers.auth

import play.twirl.api.Html
import scala.concurrent.Future

class DummyProvider extends AuthProvider {
  override def name = "dummy"
  override def callbackHandler = { request =>
    Future.successful {
      request.body.asFormUrlEncoded.map { params =>
        val username: String =
          params.get("username").map(_.head).getOrElse("anonymous")
        CallbackResult.Success(new Identity {
          override val uniqueId = "dummy:"+username
          override val name = Some(username)
          override val emailAddress = None
        })
      }.getOrElse {
          CallbackResult.Failure("Form not posted.")
      }
    }
  }
  override def loginURL = ??? // Should never be called
  override def loginButton = _ => Html(
    s"""|<form class="form-inline" action="/auth/callback" method="post">
        |  <input class="form-control" type="text"
        |         name="username" value="anonymous"/>
        |  <button class="btn btn-primary" type="submit">Login</button>
        |</form>
        |""".stripMargin
  )
}

object DummyProvider extends AuthProviderFactory {

  def apply(c: play.api.Configuration): Iterable[AuthProvider] =
    if (c.getBoolean("dummyauth").getOrElse(false))
      Some(new DummyProvider)
    else
      None

}