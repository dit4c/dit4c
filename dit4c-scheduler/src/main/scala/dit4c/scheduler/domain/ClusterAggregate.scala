package dit4c.scheduler.domain

import akka.actor._
import akka.persistence._

object ClusterAggregate {

  type ClusterType = ClusterTypes.Value
  object ClusterTypes extends Enumeration {
    val Rkt = Value("rkt")
  }

  def props(id: String): Props = Props(new ClusterAggregate(id))

  trait State
  case object Uninitialized extends State
  case class Cluster(id: String, `type`: ClusterType) extends State

  trait Command
  case class Initialize(id: String, `type`: ClusterType) extends Command
  case object GetState extends Command

  trait Event
  case class Initialized(id: String, `type`: ClusterType) extends Event

  trait Response
  case object AlreadyInitialized extends Response

}

class ClusterAggregate(aggregateId: String) extends PersistentActor with ActorLogging {

  import ClusterAggregate._

  override def persistenceId = aggregateId

  protected var state: State = Uninitialized

  override def receiveCommand: PartialFunction[Any,Unit] = {
    case Initialize(id, t) if state == Uninitialized =>
      persist(Initialized(id, t)) { e: Event =>
        updateState(e)
        sender ! state
      }
    case _: Initialize => sender ! AlreadyInitialized
    case GetState => sender ! state
  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: Event => updateState(e)
  }

  protected def updateState(e: Event): Unit = e match {
    case Initialized(id, t) =>
      this.state = Cluster(id, t)
  }

}