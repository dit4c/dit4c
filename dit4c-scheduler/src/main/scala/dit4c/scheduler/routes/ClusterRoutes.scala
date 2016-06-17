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
import play.api.libs.json.Json
import dit4c.scheduler.domain.Instance
import java.util.Base64
import dit4c.scheduler.ssh.RemoteShell

object ClusterRoutes {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val writesRSAPublicKey: Writes[RSAPublicKey] = (
      (__ \ 'kty).write[String] and
      (__ \ 'e).write[String] and
      (__ \ 'n).write[String]
  )( (k: RSAPublicKey) => (
      "RSA",
      JwtBase64.encodeString(k.getPublicExponent.toByteArray),
      JwtBase64.encodeString(k.getModulus.toByteArray)) )

  implicit val readsAddRktNode: Reads[RktClusterManager.AddRktNode] = (
      (__ \ 'host).read[String] and
      (__ \ 'port).read[Int] and
      (__ \ 'username).read[String]
  )((host: String, port: Int, username: String) =>
    RktClusterManager.AddRktNode(host, port, username, "/var/lib/dit4c-rkt"))

  implicit val readsStartInstance: Reads[RktClusterManager.StartInstance] = (
      (__ \ 'image).read[String].map(Instance.NamedImage.apply _) and
      (__ \ 'callback).read[String]
  )(RktClusterManager.StartInstance.apply _)

  implicit val writesClusterType: OWrites[ClusterAggregate.ClusterType] = (
      (__ \ 'type).write[String]
  ).contramap { (t: ClusterAggregate.ClusterType) => t.toString }

  implicit val writesInstanceStatusReport: OWrites[Instance.StatusReport] = (
      (__ \ 'state).write[String] and
      (__ \ 'image \ 'name).writeNullable[String] and
      (__ \ 'image \ 'id).writeNullable[String] and
      (__ \ 'callback).writeNullable[String] and
      (__ \ 'errors).writeNullable[Seq[String]]
  )( (sr: Instance.StatusReport) => {
    val currentState = sr.state.identifier
    sr.data match {
      case Instance.NoData => (currentState, None, None, None, None)
      case Instance.StartData(id, providedImage, resolvedImage, callbackUrl) =>
        val imageName = providedImage match {
          case Instance.NamedImage(name) => Some(name)
          case _: Instance.SourceImage => None
        }
        val imageId = resolvedImage.map(_.id)
        (currentState, imageName, imageId, Some(callbackUrl), None)
      case Instance.ErrorData(errors) =>
        (currentState, None, None, None, Some(errors))
    }
  })

  implicit val writesNodeConfig: OWrites[RktNode.NodeConfig] = (
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

  implicit val timeout = Timeout(1.minute)
  import akka.pattern.ask
  import ClusterRoutes._
  import ClusterAggregateManager._
  import ClusterAggregate._

  def routes = clusterInstanceRoutes

  val clusterInstanceRoutes =
    pathPrefix("clusters" / Segment)(clusterRoutes)

  def clusterRoutes(clusterId: String): Route = {
    pathEndOrSingleSlash {
      get {
        onSuccess(clusterAggregateManager ? GetCluster(clusterId)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case t: ClusterAggregate.ClusterType => complete(t)
        }
      }
    } ~
    pathPrefix("instances") {
      pathEndOrSingleSlash(newInstanceRoute(clusterId)) ~
      pathPrefix(Segment)(instanceRoutes(clusterId))
    } ~
    pathPrefix("nodes") {
      pathEndOrSingleSlash(newNodeRoute(clusterId)) ~
      pathPrefix(Segment)(nodeRoutes(clusterId))
    }
  }

  def newInstanceRoute(clusterId: String) =
    post {
      entity(as[RktClusterManager.StartInstance]) { cmd =>
        onSuccess(clusterAggregateManager ? ClusterCommand(clusterId, cmd)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case RktClusterManager.UnableToStartInstance =>
            complete(StatusCodes.ServiceUnavailable)
          case RktClusterManager.StartingInstance(instanceId) =>
            extractUri { thisUri =>
              val nodeUri = Uri(thisUri.path / instanceId toString)
              complete((
                  StatusCodes.Accepted,
                  Location(nodeUri) :: Nil,
                  Json.obj("id" -> instanceId)))
            }
        }
      }
    }

  def instanceRoutes(clusterId: String)(instanceId: String): Route = {
    import RktClusterManager._
    import Instance._
    pathEndOrSingleSlash {
      get {
        onSuccess(clusterAggregateManager ?
            ClusterCommand(clusterId, GetInstanceStatus(instanceId))) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case UnknownInstance => complete(StatusCodes.NotFound)
          case status: StatusReport => complete(status)
        }
      }
    } ~
    path("terminate") {
      put {
        onSuccess(clusterAggregateManager ?
            ClusterCommand(clusterId, TerminateInstance(instanceId))) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case UnknownInstance => complete(StatusCodes.NotFound)
          case TerminatingInstance => complete(StatusCodes.Accepted)
        }
      }
    }
  }

  def newNodeRoute(clusterId: String) =
    post {
      entity(as[RktClusterManager.AddRktNode]) { cmd =>
        onSuccess(clusterAggregateManager ? ClusterCommand(clusterId, cmd)) {
          case Uninitialized => complete(StatusCodes.NotFound)
          case RktClusterManager.RktNodeAdded(nodeId) =>
            onSuccess(clusterAggregateManager ?
                ClusterCommand(clusterId,
                    RktClusterManager.GetRktNodeState(nodeId))) {
              case node: RktNode.NodeConfig =>
                extractUri { thisUri => extractLog { log =>
                  val nodeUri = Uri(thisUri.path / nodeId toString)
                  val clientPublicKey = new String(
                      Base64.getEncoder().encode(
                          RemoteShell.toOpenSshPublicKey(
                              node.connectionDetails.clientKey.public)),
                      "utf8")
                  log.info("Created new node " +
                      node.connectionDetails.username + "@" +
                      node.connectionDetails.host + ":" +
                      node.connectionDetails.port +
                      " for access with RSA public key: " +
                      clientPublicKey)
                  complete((
                      StatusCodes.Created,
                      Location(nodeUri) :: Nil,
                      node))
                }}
            }
        }
      }
    }

  def nodeRoutes(clusterId: String)(nodeId: String): Route = {
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