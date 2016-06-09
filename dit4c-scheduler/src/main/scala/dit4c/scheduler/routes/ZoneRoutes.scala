package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import dit4c.scheduler.service.ZoneAggregateManager
import akka.actor.ActorRef
import akka.http.scaladsl.server.Route
import dit4c.scheduler.domain.ZoneAggregate
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.matching.Regex
import akka.http.scaladsl.server.PathMatcher

object ZoneRoutes {
  import play.api.libs.json._

  val validZoneId: Regex = """[a-zA-Z0-9]+""".r.anchored

  implicit val writesZone = Json.writes[ZoneAggregate.Zone]
}

class ZoneRoutes(zoneAggregateManager: ActorRef) extends Directives
    with PlayJsonSupport {

  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask
  import ZoneRoutes._
  import ZoneAggregateManager._
  import ZoneAggregate.{Uninitialized, Zone}

  def routes = zoneInstanceRoutes

  val zoneInstanceRoutes =
    path("zones" / validZoneId) { id =>
      zoneRoute(id)
    } ~
    path("zones" / Segment) { id =>
      // Zone identifier wasn't the format we expected, so obviously not going
      // to exist.
      complete(StatusCodes.NotFound)
    }

  def zoneRoute(id: String): Route = {
    get {
      onSuccess(zoneAggregateManager ? GetZone(id)) {
        case Uninitialized => complete(StatusCodes.NotFound)
        case zone: ZoneAggregate.Zone => complete(zone)
      }
    }
  }

}