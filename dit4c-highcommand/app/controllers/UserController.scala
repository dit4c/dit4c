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
    val etag = request.headers.get("If-None-Match")
    userDao.get(id).map {
      case Some(user) if etag == user._rev =>
        NotModified
      case Some(user) =>
        Ok(Json.toJson(user)).withHeaders("ETag" -> user._rev.get)
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
