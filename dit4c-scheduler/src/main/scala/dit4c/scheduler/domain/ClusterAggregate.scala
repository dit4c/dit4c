package dit4c.scheduler.domain

import java.time.Instant

import scala.reflect.{ClassTag, classTag}
import akka.actor._
import akka.persistence.fsm.PersistentFSM
import com.google.protobuf.timestamp.Timestamp
import dit4c.scheduler.domain.clusteraggregate.DomainEvent
import ClusterAggregate.{State, Data}

object ClusterAggregate {
  import dit4c.scheduler.domain.clusteraggregate.ClusterType

  def props(pId: String, defaultConfigProvider: DefaultConfigProvider): Props =
    Props(classOf[ClusterAggregate], pId, defaultConfigProvider)

  trait State extends BasePersistentFSMState
  case object Uninitialized extends State
  case object Active extends State

  trait Data
  case object Unmanaged extends Data
  case class ManagedCluster(
      manager: ActorRef,
      val `type`: ClusterType) extends Data

  trait Command extends BaseCommand
  case class Initialize(`type`: ClusterType) extends Command
  case object GetState extends Command

  trait Response extends BaseResponse
  trait InitializeResponse extends Response
  trait GetStateResponse extends Response
  case object UninitializedCluster extends GetStateResponse
  case class ClusterOfType(`type`: ClusterType) extends GetStateResponse
  case object ClusterInitialized extends InitializeResponse
  case object AlreadyInitialized extends InitializeResponse

}

class ClusterAggregate(val persistenceId: String, defaultConfigProvider: DefaultConfigProvider)
    extends PersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import dit4c.scheduler.domain.clusteraggregate._
  import ClusterAggregate._

  startWith(Uninitialized, Unmanaged)

  when(Uninitialized) {
    case Event(GetState, _) =>
      stay replying UninitializedCluster
    case Event(Initialize(t), _) =>
      goto(Active).applying(InitializedWithType(t)).andThen {
        case data =>
          sender ! ClusterInitialized
      }
  }

  when(Active) {
    case Event(GetState, ManagedCluster(_, t)) =>
      stay replying ClusterOfType(t)
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
    case ClusterType.Rkt => RktClusterManager.props(defaultConfigProvider.rktRunnerConfig)(context.dispatcher)
    case ClusterType.Unrecognized(v) =>
      throw new Exception(s"Unrecognised cluster type: $v")
  }

}