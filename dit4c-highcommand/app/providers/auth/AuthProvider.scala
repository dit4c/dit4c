package providers.auth

import play.api.mvc.{Request, Result}
import play.api.mvc.AnyContent
import play.twirl.api.Html
import scala.concurrent.Future
import play.api.libs.ws.WSClient

trait AuthProvider {
  def name: String
  def callbackHandler: Request[AnyContent] => Future[CallbackResult]
  def loginHandler: Request[AnyContent] => Future[Result]
}

trait AuthProviderFactory {
  def apply(config: play.api.Configuration, ws: WSClient): Iterable[AuthProvider]
}

sealed trait CallbackResult

object CallbackResult {
  object Invalid extends CallbackResult
  case class Success(val identity: Identity) extends CallbackResult
  case class Failure(val errorMessage: String) extends CallbackResult
}
