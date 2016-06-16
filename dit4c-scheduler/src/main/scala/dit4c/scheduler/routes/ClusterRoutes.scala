package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import dit4c.scheduler.service.ClusterAggregateManager
import dit4c.scheduler.domain.RktClusterManager
import akka.actor.ActorRef
import akka.http.scaladsl.server.Route
import dit4c.scheduler.domain.ClusterAggregate
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.matching.Regex
import akka.http.scaladsl.server.PathMatcher
import java.security.interfaces.RSAPublicKey
import dit4c.scheduler.domain.RktNode
import akka.event.Logging
import pdi.jwt.JwtBase64
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.Uri

object ClusterRoutes {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit def writesRSAPublicKey: Writes[RSAPublicKey] = (
      (__ \ 'kty).write[String] and
      (__ \ 'e).write[String] and
      (__ \ 'n).write[String]
  )( (k: RSAPublicKey) => (
      "RSA",
      JwtBase64.encodeString(k.getPublicExponent.toByteArray),
      JwtBase64.encodeString(k.getModulus.toByteArray)) )

  implicit def readsAddRktNode: Reads[RktClusterManager.AddRktNode] = (
      (__ \ 'host).read[String] and
      (__ \ 'port).read[Int] and
      (__ \ 'username).read[String]
  )((host: String, port: Int, username: String) =>
    RktClusterManager.AddRktNode(host, port, username, "/var/lib/dit4c-rkt"))

  implicit def writesClusterType: OWrites[ClusterAggregate.ClusterType] = (
      (__ \ 'type).write[String]
  ).contramap { (t: ClusterAggregate.ClusterType) => t.toString }

  implicit def writesNodeConfig: OWrites[RktNode.NodeConfig] = (
      (__ \ 'host).write[String] and
      (__ \ 'port).write[Int] and
      (__ \ 'username).write[String] and
      (__ \ "client-key").write[RSAPublicKey] and
      (__ \ "host-key").write[RSAPublicKey]
  )(rktNode => (
      rktNode.connectionDetails.host,
      rktNode.connectionDetails.port,
      rktNode.connectionDetails.username,
      rktNode.connectionDetails.clientKey.public,
      rktNode.connectionDetails.serverKey.public))

}

class ClusterRoutes(clusterAggregateManager: ActorRef) extends Directives
    with PlayJsonSupport {

  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask
  import ClusterRoutes._
  import ClusterAggregateManager._
  import ClusterAggregate._

  def routes = clusterInstanceRoutes

  val clusterInstanceRoutes =
    pathPrefix("clusters" / Segment)(clusterRoute)

  def clusterRoute(clusterId: String): Route = {
    pathEndOrSingleSlash {
      get {
        onSuccess(clusterAggregateManager ? GetCluster(clusterId)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case t: ClusterAggregate.ClusterType => complete(t)
        }
      }
    } ~
    pathPrefix("nodes") {
      pathEndOrSingleSlash {
        post {
          entity(as[RktClusterManager.AddRktNode]) { cmd =>
            onSuccess(clusterAggregateManager ? ClusterCommand(clusterId, cmd)) {
              case Uninitialized => complete(StatusCodes.NotFound)
              case RktClusterManager.RktNodeAdded(nodeId) =>
                onSuccess(clusterAggregateManager ?
                    ClusterCommand(clusterId,
                        RktClusterManager.GetRktNodeState(nodeId))) {
                  case node: RktNode.NodeConfig =>
                    extractUri { thisUri =>
                      val nodeUri = Uri(thisUri.path / nodeId toString)
                      complete((
                          StatusCodes.Created,
                          Location(nodeUri) :: Nil,
                          node))
                    }
                }
            }
          }
        }
      } ~
      pathPrefix(Segment)(nodeRoute(clusterId))
    }
  }

  def nodeRoute(clusterId: String)(nodeId: String): Route = {
    import RktClusterManager._
    pathEndOrSingleSlash {
      get {
        onSuccess(clusterAggregateManager ?
            ClusterCommand(clusterId, GetRktNodeState(nodeId))) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case node: RktNode.NodeConfig => complete(node)
        }
      }
    } ~
    path("confirm-keys") {
      put {
        onSuccess(clusterAggregateManager ?
            ClusterCommand(clusterId, ConfirmRktNodeKeys(nodeId))) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case node: RktNode.NodeConfig => complete(node)
        }
      }
    }
  }

}