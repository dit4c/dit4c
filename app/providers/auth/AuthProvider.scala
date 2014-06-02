package providers.auth

import play.api.mvc.Request
import play.api.mvc.AnyContent
import play.twirl.api.Html

trait AuthProvider {

  def callbackHandler: Request[AnyContent] => CallbackResult

  def loginURL: String

  def loginButton: String => Html

}

sealed trait CallbackResult

object CallbackResult {
  object Invalid extends CallbackResult
  case class Success(val identity: Identity) extends CallbackResult
  case class Failure(val errorMessage: String) extends CallbackResult
}