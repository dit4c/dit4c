package controllers

import scala.concurrent.Future
import com.google.inject.Inject
import play.api.mvc._
import play.api.libs.json._
import providers.db.CouchDB
import play.api.libs.json.Json
import java.security.MessageDigest
import providers.hipache.ContainerResolver
import rx.lang.scala.Subscription
import play.api.libs.iteratee.Concurrent

class RoutingMapController @Inject() (
    val db: CouchDB.Database,
    val containerResolver: ContainerResolver)
    extends Controller with Utils {

  def get = Action.async { implicit request =>
    for {
      computeNodes <- computeNodeDao.list
      cnMap = computeNodes.map(cn => (cn.id, cn)).toMap
      containers <- containerDao.list
    } yield {
      val json = Json.obj(
        "mappings" -> Json.toJson(containers.flatMap { c =>
          cnMap.get(c.computeNodeId).map(_.backend).map { backend =>
            val frontend = containerResolver.asFrontend(c)
            Json.obj(
              "domain" -> frontend.domain,
              "protocol" -> backend.scheme,
              "servers" -> Seq(backend.host + ":" + backend.port)
            )
          }
        })
      )
      Ok(json).withHeaders("ETag" -> etagFromJsValue(json))
    }
  }

  def feed = Action.async { implicit request =>
    Future.successful(Ok("").as("text/event-stream"))
  }

  val (eventEnumerator, eventChannel) = Concurrent.broadcast

  

  def etagFromJsValue(json: JsValue): String =
    MessageDigest.getInstance("SHA1")
      .digest(Json.stringify(json).getBytes)
      .map("%02x".format(_))
      .mkString

}
