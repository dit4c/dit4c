package services

import akka.actor._
import com.softwaremill.tagging._
import domain.ClusterAggregate
import akka.event.LoggingReceive

object ClusterAggregateManager {

  trait Command
  case class ClusterEnvelope(clusterId: String, msg: Any)

}

class ClusterAggregateManager extends Actor with ActorLogging {
  import ClusterAggregateManager._

  override def preStart = {
    context.become(sendAllToSingle(
        context.actorOf(ClusterAggregate.defaultClusterProps)
          .taggedWith[ClusterAggregate]))
  }

  // Never used
  val receive: Receive = { case _ => ??? }

  def sendAllToSingle(
      cluster: ActorRef @@ ClusterAggregate): Receive = LoggingReceive {
    case ClusterEnvelope(_, msg) =>
      cluster forward msg
  }

}