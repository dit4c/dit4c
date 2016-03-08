package providers.auth

import play.twirl.api.Html
import scala.concurrent.Future
import play.api.mvc.Results
import play.api.libs.ws.WSClient

class DummyProvider extends AuthProvider {
  override def name = "dummy"
  override def callbackHandler = { request =>
    Future.successful {
      val params = request.body.asFormUrlEncoded.getOrElse(request.queryString)
      params.get("username").map(_.head) match {
        case Some(username) =>
          CallbackResult.Success(new Identity {
            override val uniqueId = "dummy:"+username
            override val name = Some(username)
            override val emailAddress = None
          })
        case None =>
          CallbackResult.Failure("No username specified.")
      }
    }
  }

  override def loginHandler = { request =>
    Future.successful {
      val username = request.getQueryString("username")
      Results.Redirect(
          controllers.routes.AuthController.namedCallback(name).url,
          Map("username" -> username.toSeq))
    }
  }
}

object DummyProvider extends AuthProviderFactory {

  override def apply(
      c: play.api.Configuration,
      ws: WSClient): Iterable[AuthProvider] =
    if (c.getBoolean("dummyauth").getOrElse(false))
      Some(new DummyProvider)
    else
      None

}
