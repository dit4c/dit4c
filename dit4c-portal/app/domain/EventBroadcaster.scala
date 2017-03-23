package domain

import akka.actor.Actor
import akka.actor.ActorLogging
import utils.akka.ActorHelpers
import akka.event.EventStream
import domain.InstanceAggregate.StatusResponse

object EventBroadcaster {

  sealed trait Command extends BaseCommand
  case class InstanceCreationBroadcast(
      instanceId: String,
      userId: String) extends Command
  case class InstanceStatusBroadcast(
      instanceId: String,
      status: StatusResponse) extends Command

}

/**
 * Broadcasts status onto event stream.
 *
 * Note that this isn't cluster-safe, and so will need to be replaced with something using
 * distributed pub-sub if dit4c-portal is to work on multiple servers.
 */
class EventBroadcaster(eventStream: EventStream) extends Actor with ActorLogging with ActorHelpers {
  import EventBroadcaster._

  val receive = sealedReceive[Command] {
    case msg: Command =>
      eventStream.publish(msg)
  }

}