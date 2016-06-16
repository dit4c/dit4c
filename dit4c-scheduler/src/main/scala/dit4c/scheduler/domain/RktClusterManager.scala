package dit4c.scheduler.domain

import akka.persistence.PersistentActor
import akka.actor.ActorLogging
import scala.concurrent.Future
import java.security.interfaces.RSAPublicKey
import dit4c.scheduler.ssh.RemoteShell
import akka.actor.ActorRef
import java.time.Instant
import akka.actor.Props
import dit4c.scheduler.runner.{RktRunner, RktRunnerImpl}
import java.nio.file.Paths
import scala.concurrent.ExecutionContext

object RktClusterManager {
  type RktNodeId = String
  type InstanceId = String

  type HostKeyChecker = (String, Int) => Future[RSAPublicKey]
  type RktRunnerFactory =
    (RktNode.ServerConnectionDetails, String) => RktRunner

  def props(implicit ec: ExecutionContext): Props = {
    def rktRunnerFactory(
        connectionDetails: RktNode.ServerConnectionDetails,
        rktDir: String) = {
      new RktRunnerImpl(
          RemoteShell(
              connectionDetails.host,
              connectionDetails.port,
              connectionDetails.username,
              connectionDetails.clientKey.`private`,
              connectionDetails.clientKey.public,
              connectionDetails.serverKey.public),
          Paths.get(rktDir))
    }
    props(rktRunnerFactory, RemoteShell.getHostKey)
  }

  def props(
      rktRunnerFactory: RktRunnerFactory,
      hostKeyChecker: HostKeyChecker): Props =
    Props(classOf[RktClusterManager], rktRunnerFactory, hostKeyChecker)

  case class ClusterInfo(
      instanceNodeMappings: Map[InstanceId, RktNodeId] = Map.empty,
      nodeIds: Set[RktNodeId] = Set.empty)

  trait Command extends ClusterManager.Command
  case class AddRktNode(
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends Command
  case class GetRktNodeState(nodeId: RktNodeId) extends Command
  case class ConfirmRktNodeKeys(nodeId: RktNodeId) extends Command
  case class RegisterRktNode(requester: ActorRef) extends Command
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

  trait DomainEvent
  case class RktNodeRegistered(
      nodeId: String, timestamp: Instant = Instant.now) extends DomainEvent
  case class InstanceAssignedToNode(
      instanceId: String, nodeId: String,
      timestamp: Instant = Instant.now) extends DomainEvent

  trait Response
  case class RktNodeAdded(nodeId: RktNodeId) extends Response
  case class StartingInstance(instanceId: InstanceId) extends Response

}

class RktClusterManager(
    rktRunnerFactory: RktClusterManager.RktRunnerFactory,
    hostKeyChecker: RktClusterManager.HostKeyChecker)
    extends PersistentActor
    with ClusterManager
    with ActorLogging {
  import ClusterManager._
  import RktClusterManager._

  lazy val persistenceId = self.path.name

  protected var state = ClusterInfo()

  override def receiveCommand = {
    case GetStatus => sender ! state
    case AddRktNode(host, port, username, rktDir) =>
      val id = RktNode.newId
      processNodeCommand(id,
          RktNode.Initialize(host, port, username, rktDir))
    case GetRktNodeState(id) =>
      processNodeCommand(id, RktNode.GetState)
    case RegisterRktNode(requester) =>
      sender.path.name match {
        case RktNodePersistenceId(id) =>
          persist(RktNodeRegistered(id))(updateState)
          requester ! RktNodeAdded(id)
      }
    case ConfirmRktNodeKeys(id) =>
      processNodeCommand(id, RktNode.ConfirmKeys)
    case StartInstance(image: Instance.SourceImage) =>
      // TODO: Create new instance worker, then create instance with worker
  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: DomainEvent => updateState(e)
  }

  protected def updateState(e: DomainEvent): Unit = e match {
    case RktNodeRegistered(nodeId: RktNodeId, _) =>
      state = state.copy(nodeIds = state.nodeIds + nodeId)
  }

  object RktNodePersistenceId extends ChildPersistenceId("RktNode")

  def processNodeCommand(nodeId: String, command: Any) = {
    val maybeChild = context.child(RktNodePersistenceId(nodeId))
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
        RktNode.props(rktRunnerFactory, hostKeyChecker),
        RktNodePersistenceId(nodeId))
    context.watch(node)
    node
  }

}