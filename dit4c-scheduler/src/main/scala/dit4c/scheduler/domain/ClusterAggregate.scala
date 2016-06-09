package dit4c.scheduler.domain

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorLogging

object ClusterAggregate {

  def props(id: String): Props = Props(new ClusterAggregate(id))

  trait State
  case object Uninitialized extends State
  case class Cluster(id: String, `type`: ClusterType) extends State

  trait Command
  case class Initialize(id: String, `type`: ClusterType) extends Command
  case object GetState extends Command

}

class ClusterAggregate(aggregateId: String) extends Actor with ActorLogging {

  import ClusterAggregate._

  protected var state: State = Uninitialized

  def receive = {
    case Initialize(id, t) =>
      this.state = Cluster(id, t)
      sender ! state
    case GetState => sender ! state
  }

}