package controllers

import play.api._
import play.api.mvc._
import com.google.inject.Inject
import providers.auth.AuthProviders
import providers.db.CouchDB
import scala.concurrent.duration.Duration
import akka.pattern.after
import scala.concurrent.Future.successful
import play.api.libs.concurrent.Akka

class Application @Inject() (authProviders: AuthProviders)
    extends Controller {

  import play.api.libs.concurrent.Execution.Implicits._

  def main(path: String) = Action { implicit request =>
    Ok(views.html.main(authProviders.providers.toSeq, googleAnalyticsCode))
  }

  def waiting(scheme: String, host: String, uri: String) =
    Action.async { implicit request =>
      val url = new java.net.URL(
        scheme, host, "/" + uri + Option(request.rawQueryString).filter(!_.isEmpty).map("?"+_).getOrElse(""))
      request.acceptedTypes match {
        // HTML should get waiting HTML page which refreshes with JavaScript
        case range :: _ if Accepts.Html.unapply(range) =>
          successful(Ok(views.html.waiting(url)))
        // Non-HTML should just wait a bit then redirect back
        case _ =>
          val scheduler = Akka.system(Play.current).scheduler
          val waitTime = Duration(5, "seconds")
          after(waitTime, scheduler)(successful(Redirect(url.toString, 302)))
      }
    }


  private def googleAnalyticsCode: Option[String] =
    Play.current.configuration.getString("ga.code")

}
