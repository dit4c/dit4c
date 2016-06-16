package dit4c.scheduler.domain

import java.time.Instant

import scala.reflect.{ClassTag, classTag}
import akka.actor._
import akka.persistence.fsm.PersistentFSM

object ClusterAggregate {

  type ClusterType = ClusterTypes.Value
  object ClusterTypes extends Enumeration {
    val Rkt = Value("rkt")
  }

  def props(pId: String): Props = Props(classOf[ClusterAggregate], pId)

  trait State extends BasePersistentFSMState
  case object Uninitialized extends State
  case object Active extends State

  trait Data
  case object Unmanaged extends Data
  case class ManagedCluster(
      manager: ActorRef,
      val `type`: ClusterType) extends Data

  trait Command
  case class Initialize(`type`: ClusterType) extends Command
  case object GetState extends Command

  trait DomainEvent extends BaseDomainEvent
  case class InitializedWithType(
      clusterType: ClusterType,
      timestamp: Instant = Instant.now) extends DomainEvent

  trait Response
  case object AlreadyInitialized extends Response

}

class ClusterAggregate(val persistenceId: String)
    extends PersistentFSM[ClusterAggregate.State, ClusterAggregate.Data, ClusterAggregate.DomainEvent]
    with ActorLogging {
  import ClusterAggregate._

  startWith(Uninitialized, Unmanaged)

  when(Uninitialized) {
    case Event(GetState, _) =>
      stay replying Uninitialized
    case Event(Initialize(t), _) =>
      goto(Active).applying(InitializedWithType(t)).andThen {
        case data =>
          sender ! Active
      }
  }

  when(Active) {
    case Event(GetState, ManagedCluster(_, t)) =>
      stay replying t
    case Event(init: Initialize, _) =>
      stay replying AlreadyInitialized
    case Event(msg, c: ManagedCluster) =>
      c.manager forward msg
      stay
  }

  def applyEvent(
      domainEvent: DomainEvent,
      dataBeforeEvent: Data): Data = {
    domainEvent match {
      case InitializedWithType(t, _) =>
        val manager: ActorRef =
          context.actorOf(managerProps(t), managerPersistenceId(t))
        context.watch(manager)
        ManagedCluster(manager, t)
    }
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]


  private def managerPersistenceId(t: ClusterType) =
    s"${persistenceId}-${t}"

  private def managerProps(t: ClusterType) = t match {
    case ClusterTypes.Rkt => RktClusterManager.props(context.dispatcher)
  }

}