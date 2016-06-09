package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import dit4c.scheduler.domain._
import akka.actor.Props

object ZoneAggregateManager {

  sealed trait Command
  case class CreateZone(id: String, `type`: ZoneType) extends Command
  case class GetZone(id: String) extends Command

}

class ZoneAggregateManager extends Actor with ActorLogging {

  import ZoneAggregateManager._

  override def preStart {
    self ! CreateZone("default", ZoneTypes.Rkt)
  }

  def receive = {
    case CreateZone(id, t) =>
      processAggregateCommand(aggregateId(id), ZoneAggregate.Initialize(id, t))
    case GetZone(id) =>
      processAggregateCommand(aggregateId(id), ZoneAggregate.GetState)
    case ZoneAggregate.Zone("default", ZoneTypes.Rkt) =>
      // Expected from preStart
    case unknownMessage =>
      log.error(s"Unknown message: $unknownMessage")
  }

  def aggregateId(id: String) = s"zone-$id"

  def processAggregateCommand(aggregateId: String, command: ZoneAggregate.Command) = {
    val maybeChild = context.child(aggregateId)
    maybeChild match {
      case Some(child) =>
        log.debug(s"Forwarding $command to aggregate: $aggregateId")
        child forward command
      case None =>
        log.debug(s"Creating aggregate: $aggregateId")
        val child = createZoneAggregate(aggregateId)
        log.debug(s"Forwarding $command to aggregate: $aggregateId")
        child forward command
    }
  }

  def aggregateProps(aggregateId: String): Props = {
    ZoneAggregate.props(aggregateId)
  }

  protected def createZoneAggregate(aggregateId: String): ActorRef = {
    val agg = context.actorOf(aggregateProps(aggregateId), aggregateId)
    context.watch(agg)
    agg
  }

}