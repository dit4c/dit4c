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
import play.api.libs.iteratee._
import providers.RoutingMapEmitter.ReplaceAllRoutes
import play.api.libs.concurrent.Promise
import scala.concurrent.duration._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

class RoutingMapController @Inject() (
    val db: CouchDB.Database,
    val routingMapEmitter: RoutingMapEmitter)
    extends Controller with Utils {

  def comment(msg: String) = new EventSource.Event(msg, None, None) {
    override lazy val formatted = """(?m)^""".r.replaceAllIn(msg, "; ")+"\n"
  }

  val keepAlive: Enumerator[EventSource.Event] = {
    val fmt = ISODateTimeFormat.dateHourMinuteSecond()
    Enumerator.repeatM {
      Promise.timeout(comment(fmt.print(new DateTime())), 10.seconds)
    }
  }

  def feed = Action.async { implicit request =>
    Future.successful {
      val feed = routingMapEmitter.newFeed &>
         Enumeratee.map(Json.toJson(_)) &>
         EventSource() interleave keepAlive
      Ok.stream(feed).as("text/event-stream")
    }
  }

  implicit val routeWrites: Writes[RoutingMapEmitter.Route] = Writes { route =>
    Json.obj(
      "domain" -> route.frontend.domain,
      "name" -> route.frontend.name,
      "headers" -> Json.obj(
        "X-Server-Name" -> route.frontend.name
      ),
      "upstream" -> Json.obj(
        "scheme" -> route.backend.scheme,
        "host" -> route.backend.host,
        "port" -> route.backend.port
      )
    )
  }

  implicit val rarWrites = Writes[RoutingMapEmitter.Event] {
    case RoutingMapEmitter.ReplaceAllRoutes(routes) =>
      Json.obj(
          "op" -> "replace-all-routes",
          "routes" -> Json.toJson(routes))
    case RoutingMapEmitter.SetRoute(route) =>
      Json.obj(
          "op" -> "set-route",
          "route" -> Json.toJson(route))
    case RoutingMapEmitter.DeleteRoute(route) =>
      Json.obj(
          "op" -> "delete-route",
          "route" -> Json.toJson(route))
  }

}
