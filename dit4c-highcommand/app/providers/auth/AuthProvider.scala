package providers.auth

import play.api.mvc.Request
import play.api.mvc.AnyContent
import play.twirl.api.Html
import scala.concurrent.Future

trait AuthProvider {
  def name: String
  def callbackHandler: Request[AnyContent] => Future[CallbackResult]
  def loginURL: String
}

trait AuthProviderFactory {
  def apply(config: play.api.Configuration): Iterable[AuthProvider]
}

sealed trait CallbackResult

object CallbackResult {
  object Invalid extends CallbackResult
  case class Success(val identity: Identity) extends CallbackResult
  case class Failure(val errorMessage: String) extends CallbackResult
}
