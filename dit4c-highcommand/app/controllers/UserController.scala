package controllers

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.google.inject.Inject

import models.User
import models.UserDAO
import play.api.libs.json._
import play.api.mvc._
import providers.db.CouchDB

class UserController @Inject() (val db: CouchDB.Database)
    extends Controller with Utils {


  def get(id: String) = Authenticated.async { implicit request =>
    userDao.get(id).map {
      case Some(user) =>
        ifNoneMatch(user._rev.get) {
          Ok(Json.toJson(user))
        }
      case None =>
        NotFound
    }
  }

  def currentUser = Action.async { implicit request =>
    fetchUser.map {
      case Some(user) =>
        Redirect(controllers.routes.UserController.get(user.id))
          .withHeaders("Cache-Control" -> "private, must-revalidate")
      case None =>
        NotFound
    }
  }

  def mergeUser = Action { implicit request =>
    request.session.get("mergeUserId") match {
      case Some(userId) =>
        Redirect(controllers.routes.UserController.get(userId))
          .withHeaders("Cache-Control" -> "private, must-revalidate")
      case None =>
        NotFound
    }
  }

}
