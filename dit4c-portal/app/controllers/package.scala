import scala.concurrent.Future

package object controllers {
  import play.api.mvc._

  class UserRequest[A](val userId: Option[String], request: Request[A]) extends WrappedRequest[A](request)

  object UserAction extends
      ActionBuilder[UserRequest] with ActionTransformer[Request, UserRequest] {
    def transform[A](request: Request[A]) = Future.successful {
      new UserRequest(request.session.get("user-id"), request)
    }
  }

  case class LoginData(userId: String)
}