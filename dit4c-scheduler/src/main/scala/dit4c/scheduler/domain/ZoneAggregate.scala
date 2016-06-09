package dit4c.scheduler.domain

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorLogging

object ZoneAggregate {

  def props(id: String): Props = Props(new ZoneAggregate(id))

  trait State
  case object Uninitialized extends State
  case class Zone(id: String, `type`: ZoneType) extends State

  trait Command
  case class Initialize(id: String, `type`: ZoneType) extends Command
  case object GetState extends Command

}

class ZoneAggregate(aggregateId: String) extends Actor with ActorLogging {

  import ZoneAggregate._

  protected var state: State = Uninitialized

  def receive = {
    case Initialize(id, t) =>
      this.state = Zone(id, t)
      sender ! state
    case GetState => sender ! state
  }

}