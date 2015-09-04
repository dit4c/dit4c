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
import providers.RoutingMapEmitter
import play.api.libs.EventSource
import play.api.libs.iteratee.Enumeratee
import providers.RoutingMapEmitter.ReplaceAllRoutes

class RoutingMapController @Inject() (
    val db: CouchDB.Database,
    val routingMapEmitter: RoutingMapEmitter)
    extends Controller with Utils {

  def feed = Action.async { implicit request =>
    Future.successful {
      val feed = routingMapEmitter.newFeed &>
         Enumeratee.map(Json.toJson(_)) &>
         EventSource()
      Ok.stream(feed).as("text/event-stream")
    }
  }

  implicit val routeWrites: Writes[RoutingMapEmitter.Route] = Writes { route =>
    Json.obj(
      "name" -> route.frontend.name,
      "domain" -> route.frontend.domain,
      "scheme" -> route.backend.scheme,
      "servers" -> Json.arr(route.backend.host+":"+route.backend.port)
    )
  }

  implicit val rarWrites = Writes[RoutingMapEmitter.Event] {
    case RoutingMapEmitter.ReplaceAllRoutes(routes) =>
      Json.obj(
          "event" -> "replace-all-routes",
          "routes" -> Json.toJson(routes))
    case RoutingMapEmitter.SetRoute(route) =>
      Json.obj(
          "event" -> "set-route",
          "route" -> Json.toJson(route))
    case RoutingMapEmitter.DeleteRoute(route) =>
      Json.obj(
          "event" -> "delete-route",
          "route" -> Json.toJson(route))
  }

}
