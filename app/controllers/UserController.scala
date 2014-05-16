package controllers

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.google.inject.Inject

import models.User
import models.UserDAO
import play.api.libs.json._
import play.api.mvc._
import providers.db.CouchDB

class UserController @Inject() (db: CouchDB.Database) extends Controller {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  def currentUser = Action.async { implicit request =>
    fetchUser.map {
      case Some(user) =>
        val json = Json.obj(
          "id"    -> user._id,
          "name"  -> user.name,
          "email" -> user.email
        )
        Ok(json)
      case None =>
        NotFound
    }
  }

  private lazy val userDao = new UserDAO(db)

  protected def fetchUser(implicit request: Request[_]): Future[Option[User]] =
    request.session.get("userId")
      .map(userDao.get) // Get user if userId exists
      .getOrElse(Future.successful(None))

}