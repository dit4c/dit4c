package domain

import akka.actor._

class ClusterManager(imageServerConfig: ImageServerConfig) extends Actor with ActorLogging {

  val receive: Receive = {
    case SchedulerAggregate.ClusterEnvelope(clusterId, msg) =>
      clusterRef(clusterId) forward msg
    case msg: AccessPassManager.Command =>
      context.parent forward msg
    case msg: SchedulerAggregate.SendSchedulerMessage =>
      context.parent forward msg
  }

  def clusterRef(id: String): ActorRef =
    context.child(id).getOrElse {
      context.actorOf(
          Props(classOf[Cluster], imageServerConfig),
          id)
    }

}