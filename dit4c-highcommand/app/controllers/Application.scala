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
import play.api.libs.Codecs
import scala.util.Try

class Application @Inject() (
    authProviders: AuthProviders,
    webConfig: providers.WebConfig,
    val db: CouchDB.Database)
    extends Controller with Utils {

  import http.HeaderNames.CACHE_CONTROL
  
  private val mainTmplETag = classBasedETag(views.html.main.getClass)
  private val waitingTmplETag = classBasedETag(views.html.main.getClass)

  def main(path: String) = Action { implicit request =>
    ifNoneMatch(mainTmplETag("")) {
      Ok(views.html.main(authProviders.providers.toSeq, webConfig))
        .withHeaders(CACHE_CONTROL -> "public, max-age=1")
    }
  }

  def waiting(scheme: String, host: String, uri: String) =
    Action.async { implicit request =>
      val url = new java.net.URL(
        scheme, host, "/" + uri + Option(request.rawQueryString).filter(!_.isEmpty).map("?"+_).getOrElse(""))
      request.acceptedTypes match {
        // HTML should get waiting HTML page which refreshes with JavaScript
        case range :: _ if Accepts.Html.unapply(range) =>
          successful {
            ifNoneMatch(waitingTmplETag(url.toString)) {
              Ok(views.html.waiting(url))
                .withHeaders(CACHE_CONTROL -> "public, max-age=1")
            }
          }
        // Non-HTML should just wait a bit then redirect back
        case _ =>
          val scheduler = Akka.system(Play.current).scheduler
          val waitTime = Duration(5, "seconds")
          after(waitTime, scheduler) { 
            successful {
              Redirect(url.toString, 302)
                .withHeaders(CACHE_CONTROL -> "no-cache")
            }
          }
      }
    }

  /**
   * Report application health.
   *
   * Will ping CouchDB, as app is effectively dead if it can't reach the DB.
   *
   */
  def health(isHeadRequest: Boolean) = Action.async { implicit request =>
    for {
      dbExists <- db.asSohvaDb.exists
    } yield {
      if (dbExists)
        if (isHeadRequest)
          Ok(Results.EmptyContent())
        else
          NoContent
      else
        InternalServerError("Database does not exist.")
    }
  }

  def classBasedETag(c: Class[_]) = {
    val urlConn = c.getResource(c.getSimpleName + ".class").openConnection
    val base = 
      try {
        Codecs.sha1 {
          urlConn.getURL.toExternalForm + urlConn.getLastModified
        }
      } finally {
        urlConn.getInputStream.close
      }
    { suffix: String => Codecs.sha1(base + suffix) }
  }
    
}
