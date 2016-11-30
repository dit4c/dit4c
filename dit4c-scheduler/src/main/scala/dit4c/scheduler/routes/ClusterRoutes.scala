package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import dit4c.scheduler.service.ClusterAggregateManager
import dit4c.scheduler.domain.RktClusterManager
import akka.actor.ActorRef
import akka.http.scaladsl.server.Route
import dit4c.scheduler.domain.ClusterAggregate
import dit4c.scheduler.domain.clusteraggregate.ClusterType
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
import java.math.BigInteger
import dit4c.common.KeyHelpers._
import org.bouncycastle.openpgp.PGPPublicKey

object ClusterRoutes {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val writesBigInteger: Writes[BigInteger] =
    Writes { bi: BigInteger =>
      JsString(JwtBase64.encodeString(bi.toByteArray))
    }

  implicit val writesRSAPublicKey: Writes[RSAPublicKey] = (
      (__ \ 'jwk \ 'kty).write[String] and
      (__ \ 'jwk \ 'e).write[BigInteger] and
      (__ \ 'jwk \ 'n).write[BigInteger] and
      (__ \ 'ssh \ 'fingerprints).write[Seq[String]] and
      (__ \ 'ssh \ 'openssh).write[String] and
      (__ \ 'ssh \ 'ssh2).write[String]
  )( (k: RSAPublicKey) => (
      "RSA",
      k.getPublicExponent,
      k.getModulus,
      Seq("MD5", "SHA-256").map(k.ssh.fingerprint),
      k.ssh.authorizedKeys,
      k.ssh.pem) )

  implicit val readsAddRktNode: Reads[RktClusterManager.AddRktNode] = (
      (__ \ 'host).read[String] and
      (__ \ 'port).read[Int] and
      (__ \ 'username).read[String]
  )((host: String, port: Int, username: String) =>
    RktClusterManager.AddRktNode(host, port, username, "/var/lib/dit4c-rkt"))

  implicit val writesClusterType: OWrites[ClusterType] = (
      (__ \ 'type).write[String]
  ).contramap { (t: ClusterType) => t.toString }

  implicit val writesSigningKey: Writes[Instance.InstanceKeys] =
    Writes { key =>
      Json.toJson(key.asPGPPublicKeyRing.getPublicKey.asRSAPublicKey)
    }

  implicit val writesInstanceStatusReport: OWrites[Instance.StatusReport] = (
      (__ \ 'state).write[String] and
      (__ \ 'image \ 'name).writeNullable[String] and
      (__ \ 'image \ 'id).writeNullable[String] and
      (__ \ 'portal).writeNullable[String] and
      (__ \ 'key).writeNullable[Instance.InstanceKeys] and
      (__ \ 'errors).writeNullable[Seq[String]]
  )( (sr: Instance.StatusReport) => {
    val currentState = sr.state.identifier
    sr.data match {
      case Instance.NoData => (currentState, None, None, None, None, None)
      case Instance.StartData(id, providedImage, resolvedImage, portalUri, instanceKey) =>
        (currentState, Some(providedImage), resolvedImage, Some(portalUri), instanceKey, None)
      case Instance.SaveData(instanceId, instanceKey, uploadHelperImage, imageServer, portalUri) =>
        (currentState, None, None, Some(portalUri), Some(instanceKey), None)
      case Instance.DiscardData(instanceId) =>
        (currentState, None, None, None, None, None)
      case Instance.ErrorData(id, errors) =>
        (currentState, None, None, None, None, Some(errors))
    }
  })

  case class StatusReportWithId(id: String, sr: Instance.StatusReport)

  implicit val writesInstanceStatusReportWithId: OWrites[StatusReportWithId] =
    OWrites { obj =>
      writesInstanceStatusReport.writes(obj.sr).deepMerge {
        obj.sr.data match {
          case d: Instance.StartData if d.keys.isDefined =>
            (__ \ 'key \ 'jwk \ 'kid).write[String].writes( obj.id)
          case _ => Json.obj()
        }
      }
    }

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
          case UninitializedCluster => complete(StatusCodes.NotFound)
          case ClusterOfType(t) => complete(t)
        }
      }
    } ~
    pathPrefix("instances") {
      pathPrefix(Segment)(instanceRoutes(clusterId))
    } ~
    pathPrefix("nodes") {
      pathEndOrSingleSlash(newNodeRoute(clusterId)) ~
      pathPrefix(Segment)(nodeRoutes(clusterId))
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
          case status: StatusReport => complete(StatusReportWithId(instanceId, status))
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
              case RktNode.Exists(node) =>
                extractUri { thisUri => extractLog { log =>
                  val nodeUri = Uri(thisUri.path / nodeId toString)
                  val clientPublicKey = node.connectionDetails.clientKey.public.ssh.authorizedKeys
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
          case RktNode.DoesNotExist => complete(StatusCodes.NotFound)
          case RktNode.Exists(node) => complete(node)
        }
      }
    } ~
    path("confirm-keys") {
      put {
        onSuccess(clusterAggregateManager ?
            ClusterCommand(clusterId, ConfirmRktNodeKeys(nodeId))) {
          case RktNode.DoesNotExist => complete(StatusCodes.NotFound)
          case RktNode.ConfirmKeysResponse(node) => complete(node)
        }
      }
    }
  }

}
