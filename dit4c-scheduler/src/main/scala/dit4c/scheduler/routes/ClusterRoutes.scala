package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import dit4c.scheduler.service.ClusterAggregateManager
import akka.actor.ActorRef
import akka.http.scaladsl.server.Route
import dit4c.scheduler.domain.ClusterAggregate
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.matching.Regex
import akka.http.scaladsl.server.PathMatcher

object ClusterRoutes {
  import play.api.libs.json._

  val validClusterId: Regex = """[a-zA-Z0-9]+""".r.anchored

  implicit val writesCluster = Json.writes[ClusterAggregate.Cluster]
}

class ClusterRoutes(zoneAggregateManager: ActorRef) extends Directives
    with PlayJsonSupport {

  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask
  import ClusterRoutes._
  import ClusterAggregateManager._
  import ClusterAggregate.{Uninitialized, Cluster}

  def routes = clusterInstanceRoutes

  val clusterInstanceRoutes =
    pathPrefix("clusters" / validClusterId) { id =>
      clusterRoute(id)
    } ~
    path("clusters" / Segment) { id =>
      // Cluster identifier wasn't the format we expected, so obviously not going
      // to exist.
      complete(StatusCodes.NotFound)
    }

  def clusterRoute(id: String): Route = {
    pathEndOrSingleSlash {
      get {
        onSuccess(zoneAggregateManager ? GetCluster(id)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case zone: ClusterAggregate.Cluster => complete(zone)
        }
      }
    }
  }

}