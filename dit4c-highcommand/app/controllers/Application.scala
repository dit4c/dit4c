package controllers

import play.api._
import play.api.mvc._
import com.google.inject.Inject
import providers.auth.AuthProviders
import providers.db.CouchDB

class Application @Inject() (authProviders: AuthProviders)
    extends Controller {

  def main(path: String) = Action { implicit request =>
    Ok(views.html.main(authProviders.providers.toSeq))
  }

  def waiting = Action { implicit request =>
    Ok(views.html.waiting()).withHeaders("max-age" -> "3600")
  }

}
