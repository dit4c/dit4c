package controllers

import play.api._
import play.api.mvc._
import javax.inject.{Inject,Singleton}
import providers.auth.AuthProviders
import providers.db.CouchDB
import scala.concurrent.duration.Duration
import akka.pattern.after
import scala.concurrent.Future.successful
import play.api.libs.concurrent.Akka
import play.api.libs.Codecs
import scala.util.Try
import play.api.http.ContentTypes
import play.api.libs.json._
import akka.actor.ActorSystem

@Singleton
class Application @Inject() (
    actorSystem: ActorSystem,
    authProviders: AuthProviders,
    webConfig: providers.WebConfig,
    val db: CouchDB.Database)
    extends Controller with Utils {

  import http.HeaderNames._

  private val mainTmplETag = classBasedETag(views.html.main.getClass)
  private val waitingTmplETag = classBasedETag(views.html.main.getClass)

  private val seenJsAssets = akka.agent.Agent(Set.empty[String])

  def main(path: String) = Action { implicit request =>
    val jsAssets = seenJsAssets.get.toSeq.sorted
    ifNoneMatch(mainTmplETag(jsAssets.mkString(","))) {
      val linkHeader: Option[String] =
        Option(
          jsAssets
            .map(path => routes.Assets.versioned(s"javascripts/$path").toString)
            .map(v => s"<$v>; rel=preload")
            .mkString(", ")
          ).filter(_ != "")
      Ok(views.html.main(authProviders.providers.toSeq, webConfig, requireJsConfig(jsAssets)))
        .withHeaders(CACHE_CONTROL -> "public, max-age=1")
        .withHeaders(linkHeader.map("Link" -> _).toSeq: _*)
    }
  }

  def jsAsset(file: Assets.Asset) = Action.async { implicit request =>
    val path = request.path.stripPrefix(routes.Application.jsAsset("").toString)
    val isVersioned = request.path.split("/").last.takeWhile(_ != '-') match {
      case s if s.length >= 20 && s.matches("[0-9a-f]+") => true
      case _ => false
    }
    Assets.versioned("/public/javascripts", file)(request).map { response =>

      if (!isVersioned &&
          response.header.status == 200 &&
          response.header.headers(CONTENT_TYPE).contains("javascript")) {
        seenJsAssets.send(_ + path)
      }

      response
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
          val scheduler = actorSystem.scheduler
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

  def requireJsConfig(jsAssets: Seq[String]): String = {
    val extra =
      Json.obj(
        "paths" -> jsAssets.foldLeft(Json.obj()) { case (obj, path) =>
          obj ++ Json.obj(path.stripSuffix(".js") -> routes.Assets.versioned(s"javascripts/$path").toString.stripPrefix(routes.Application.jsAsset("").toString).stripSuffix(".js"))
        }
      )
    org.webjars.RequireJS.getSetupJavaScript(routes.WebJarAssets.at("").url)
      .replace("// All of the WebJar configs", s"requirejs.config($extra);")
  }


}
