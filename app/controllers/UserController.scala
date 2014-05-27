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

  def currentUser = Action.async { implicit request =>
    fetchUser.map {
      case Some(user) =>
        val json = Json.obj(
          "id"    -> user.id,
          "name"  -> user.name,
          "email" -> user.email
        )
        Ok(json)
      case None =>
        NotFound
    }
  }

}