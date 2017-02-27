package domain.instance

import domain.InstanceAggregate
import domain.BaseResponse
import akka.actor.Actor
import akka.actor.ActorLogging
import domain.BaseCommand
import utils.akka.ActorHelpers
import akka.event.EventStream
import domain.InstanceAggregate.StatusResponse

object StatusBroadcaster {

  sealed trait Command extends BaseCommand
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
class StatusBroadcaster(eventStream: EventStream) extends Actor with ActorLogging with ActorHelpers {
  import StatusBroadcaster._

  val receive = sealedReceive[Command] {
    case msg: InstanceStatusBroadcast =>
      eventStream.publish(msg)
  }

}