package dit4c.scheduler.domain

import akka.actor._
import akka.persistence._
import scala.util.Random
import scala.concurrent.Future
import java.security.interfaces.RSAPublicKey
import dit4c.scheduler.ssh.RemoteShell
import java.time.Instant

object ClusterAggregate {

  type ClusterId = String
  type InstanceId = String
  type RktNodeId = String

  type ClusterType = ClusterTypes.Value
  object ClusterTypes extends Enumeration {
    val Rkt = Value("rkt")
  }

  def props(
      pId: String,
      fetchSshHostKey: (String, Int) => Future[RSAPublicKey] = RemoteShell.getHostKey): Props =
        Props(classOf[ClusterAggregate], pId, fetchSshHostKey)

  trait State
  case object Uninitialized extends State
  trait Cluster extends State {
    def id: String
    def `type`: ClusterType
  }
  case class RktCluster(
      id: String,
      instanceNodeMappings: Map[InstanceId, RktNodeId] = Map.empty,
      nodeIds: Set[RktNodeId] = Set.empty) extends Cluster {
    val `type` = ClusterTypes.Rkt
  }

  trait Command
  case class Initialize(id: String, `type`: ClusterType) extends Command
  case object GetState extends Command
  trait RktClusterCommand extends Command
  case class AddRktNode(
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends RktClusterCommand
  case class GetRktNodeState(nodeId: RktNodeId) extends RktClusterCommand
  case class ConfirmRktNodeKeys(nodeId: RktNodeId) extends RktClusterCommand
  case class RegisterRktNode(requester: ActorRef) extends RktClusterCommand
  case class StartInstance(image: Instance.SourceImage) extends Command
  case class TerminateInstance(id: Instance.Id) extends Command

  trait InstanceRelatedCommand extends Command { def instanceId: Instance.Id }
  case class CommandForInstance(
      instanceId: Instance.Id,
      cmd: Instance.Command) extends InstanceRelatedCommand
  case class DirectiveFromInstance(
      instanceId: Instance.Id,
      directive: InstanceDirective) extends InstanceRelatedCommand

  trait InstanceDirective
  object InstanceDirectives {
    case class Fetch(image: Instance.NamedImage) extends InstanceDirective
    case class Start(image: Instance.LocalImage) extends InstanceDirective
    case object Stop extends InstanceDirective
  }

  trait Event extends BaseDomainEvent
  case class Initialized(
      cluster: Cluster, timestamp: Instant = Instant.now) extends Event
  trait RktEvent extends Event
  case class RktNodeRegistered(
      nodeId: String, timestamp: Instant = Instant.now) extends RktEvent
  case class InstanceAssignedToNode(
      instanceId: String, nodeId: String,
      timestamp: Instant = Instant.now) extends RktEvent

  trait Response
  case object AlreadyInitialized extends Response
  case class RktNodeAdded(nodeId: RktNodeId) extends Response
  case class StartingInstance(instanceId: InstanceId) extends Response

}

class ClusterAggregate(
    val persistenceId: String,
    fetchSshHostKey: (String, Int) => Future[RSAPublicKey]) extends PersistentActor
    with ActorLogging {

  import ClusterAggregate._

  protected var state: State = Uninitialized

  override def receiveCommand = initial

  val initial: Receive = {
    case Initialize(id, t) if state == Uninitialized =>
      val newState = t match {
        case ClusterTypes.Rkt => RktCluster(id)
      }
      persist(Initialized(newState)) { e: Event =>
        updateState(e)
        sender ! state
      }
    case GetState => sender ! state
  }

  val commonBehaviour: Receive = {
    case _: Initialize => sender ! AlreadyInitialized
    case GetState => sender ! state
  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: Event => updateState(e)
  }

  protected def updateState(e: Event): Unit = e match {
    case Initialized(newState: RktCluster, _) =>
      context.become(RktClusterBehaviour.receive)
      this.state = newState
    case RktNodeRegistered(nodeId: RktNodeId, _) =>
      state match {
        case cluster: RktCluster =>
          state = cluster.copy(nodeIds = cluster.nodeIds + nodeId)
      }
  }

  def processInstanceCommand(instanceId: String, command: Instance.Command) = {
    val maybeChild = context.child(PersistenceId.Instance(instanceId))
    maybeChild match {
      case Some(child) =>
        child forward command
      case None =>
        val child = createInstanceActor(instanceId)
        child forward command
    }
  }

  protected def createInstanceActor(instanceId: String): ActorRef = {
    val node = context.actorOf(Props[Instance],
        PersistenceId.Instance(instanceId))
    context.watch(node)
    node
  }

  object RktClusterBehaviour {
    val receive: Receive = commonBehaviour orElse {
      case AddRktNode(host, port, username, rktDir) =>
        val id = RktNode.newId
        processNodeCommand(id,
            RktNode.Initialize(host, port, username, rktDir))
      case GetRktNodeState(id) =>
        processNodeCommand(id, RktNode.GetState)
      case RegisterRktNode(requester) =>
        sender.path.name match {
          case PersistenceId.RktNode(id) =>
            persist(RktNodeRegistered(id)) { e: Event =>
              updateState(e)
            }
            requester ! RktNodeAdded(id)
        }
      case ConfirmRktNodeKeys(id) =>
        processNodeCommand(id, RktNode.ConfirmKeys)
      case StartInstance(image: Instance.SourceImage) =>
        val id = Instance.newId
        // Initiate instance creation
        processInstanceCommand(id, Instance.Initiate(image))
        // Immediately reply
        sender ! StartingInstance(id)
      case directive: InstanceDirective =>
        sender.path.name match {
          case PersistenceId.Instance(id) =>
            getNodeForInstance(id) match {
              case Right(nodeId) =>
                processNodeCommand(nodeId, DirectiveFromInstance(id, directive))
              case Left(msg) =>
                sender ! Instance.Error(msg)
            }
        }
    }

    def getNodeForInstance(instanceId: InstanceId): Either[String, RktNodeId] =
      state match {
        case c: RktCluster if c.instanceNodeMappings.contains(instanceId) =>
          Right(c.instanceNodeMappings(instanceId))
        case c: RktCluster =>
          Random.shuffle(c.nodeIds).headOption
            .map { nodeId =>
              persist(InstanceAssignedToNode(instanceId, nodeId))(updateState)
              nodeId
            }
            .toRight("Unable to allocate node for instance")
      }

    def processNodeCommand(nodeId: String, command: Any) = {
      val maybeChild = context.child(PersistenceId.RktNode(nodeId))
      maybeChild match {
        case Some(child) =>
          child forward command
        case None =>
          val child = createNodeActor(nodeId)
          child forward command
      }
    }

    protected def createNodeActor(nodeId: String): ActorRef = {
      val node = context.actorOf(
          RktNode.props(fetchSshHostKey),
          PersistenceId.RktNode(nodeId))
      context.watch(node)
      node
    }

  }

  object PersistenceId {
    type ChildId = String
    val separator = "-"

    object Instance extends Child("Instance")
    object RktNode extends Child("RktNode")

    abstract class Child(childTypeId: String) {
      def apply(childId: ChildId) =
        Seq(persistenceId, childTypeId, childId).mkString(separator)

      def unapply(childPersistenceId: String): Option[ChildId] = {
        val prefix =
          Seq(persistenceId, childTypeId).mkString(separator) + separator
        Some(childPersistenceId)
          .filter(_.startsWith(prefix))
          .map(_.stripPrefix(prefix))
      }
    }
  }


}