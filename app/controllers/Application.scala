package controllers

import play.api._
import play.api.mvc._
import com.google.inject.Inject
import providers.auth.AuthProvider
import providers.db.CouchDB

class Application @Inject() (
    authProvider: AuthProvider,
    db: CouchDB.Database)
    extends Controller {

  def main(path: String) = Action { implicit request =>
    Ok(views.html.main(authProvider.loginButton))
  }
}
