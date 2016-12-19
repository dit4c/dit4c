package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import dit4c.scheduler.domain._
import akka.actor.Props
import scala.util.matching.Regex

object ClusterManager {

  type ClusterId = String

  def props(
      defaultConfigProvider: ConfigProvider,
      knownClusters: Map[ClusterManager.ClusterId, ClusterInfo]): Props =
    Props(classOf[ClusterManager], defaultConfigProvider, knownClusters)

  sealed trait Command extends BaseCommand
  case class GetCluster(id: String) extends Command
  case class ClusterCommand(
      clusterId: String, cmd: Any) extends Command

  val validClusterId: Regex = """[a-zA-Z0-9]+""".r.anchored
  def isValidClusterId(id: String): Boolean =
    validClusterId.unapplySeq(id).isDefined
}

class ClusterManager(
    defaultConfigProvider: ConfigProvider,
    knownClusters: Map[ClusterManager.ClusterId, ClusterInfo])
    extends Actor with ActorLogging {
  import ClusterManager._

  def receive = {
    case GetCluster(id) if !isValidClusterId(id) =>
      // Invalid IDs will forever be uninitialized clusters
      sender ! Cluster.Uninitialized
    case GetCluster(id) =>
      cluster(id) forward Cluster.GetState
    case ClusterCommand(id, msg) =>
      cluster(id) forward msg
    case unknownMessage =>
      log.error(s"Unknown message: $unknownMessage")
  }

  protected def cluster(clusterId: String): ActorRef =
    context.child(clusterId).getOrElse {
      val agg = context.actorOf(
          clusterProps(clusterId), clusterId)
      context.watch(agg)
      agg
    }

  protected def clusterProps(clusterId: String): Props =
    Cluster.props(
        knownClusters.get(clusterId),
        defaultConfigProvider)

}