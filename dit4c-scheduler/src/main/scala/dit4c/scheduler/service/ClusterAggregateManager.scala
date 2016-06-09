package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import dit4c.scheduler.domain._
import akka.actor.Props

object ClusterAggregateManager {

  sealed trait Command
  case class CreateCluster(id: String, `type`: ClusterType) extends Command
  case class GetCluster(id: String) extends Command

}

class ClusterAggregateManager extends Actor with ActorLogging {

  import ClusterAggregateManager._

  override def preStart {
    self ! CreateCluster("default", ClusterTypes.Rkt)
  }

  def receive = {
    case CreateCluster(id, t) =>
      processAggregateCommand(aggregateId(id), ClusterAggregate.Initialize(id, t))
    case GetCluster(id) =>
      processAggregateCommand(aggregateId(id), ClusterAggregate.GetState)
    case ClusterAggregate.Cluster("default", ClusterTypes.Rkt) =>
      // Expected from preStart
    case unknownMessage =>
      log.error(s"Unknown message: $unknownMessage")
  }

  def aggregateId(id: String) = s"zone-$id"

  def processAggregateCommand(aggregateId: String, command: ClusterAggregate.Command) = {
    val maybeChild = context.child(aggregateId)
    maybeChild match {
      case Some(child) =>
        log.debug(s"Forwarding $command to aggregate: $aggregateId")
        child forward command
      case None =>
        log.debug(s"Creating aggregate: $aggregateId")
        val child = createClusterAggregate(aggregateId)
        log.debug(s"Forwarding $command to aggregate: $aggregateId")
        child forward command
    }
  }

  def aggregateProps(aggregateId: String): Props = {
    ClusterAggregate.props(aggregateId)
  }

  protected def createClusterAggregate(aggregateId: String): ActorRef = {
    val agg = context.actorOf(aggregateProps(aggregateId), aggregateId)
    context.watch(agg)
    agg
  }

}