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
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.Random
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure

object RktClusterManager {
  type RktNodeId = String
  type InstanceId = String

  type HostKeyChecker = (String, Int) => Future[RSAPublicKey]
  type RktRunnerFactory =
    (RktNode.ServerConnectionDetails, String) => RktRunner

  def props(clusterId: String, config: ConfigProvider)(implicit ec: ExecutionContext): Props = {
    def rktRunnerFactory(
        connectionDetails: RktNode.ServerConnectionDetails,
        rktDir: String) = {
      new RktRunnerImpl(
          RemoteShell(
              connectionDetails.host,
              connectionDetails.port,
              connectionDetails.username,
              config.sshKeys,
              Future.successful(
                  connectionDetails.serverKey.public)),
          config.rktRunnerConfig)
    }
    props(clusterId, rktRunnerFactory, RemoteShell.getHostKey)
  }

  def props(
      clusterId: String,
      rktRunnerFactory: RktRunnerFactory,
      hostKeyChecker: HostKeyChecker): Props =
    Props(classOf[RktClusterManager], clusterId, rktRunnerFactory, hostKeyChecker)

  case class ClusterInfo(
      nodeIds: Set[RktNodeId] = Set.empty)

  trait Command extends ClusterManager.Command
  case object Shutdown extends Command
  case class AddRktNode(
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends Command
  case class GetRktNodeState(nodeId: RktNodeId) extends Command
  case class ConfirmRktNodeKeys(nodeId: RktNodeId) extends Command
  case class RegisterRktNode(nodeId: RktNodeId) extends Command
  case class StartInstance(
      instanceId: String, image: String, portalUri: String) extends Command
  case class GetInstanceStatus(id: Instance.Id) extends Command
  case class InstanceEnvelope(id: Instance.Id, msg: Instance.Command) extends Command

  trait Response extends ClusterManager.Response
  case class CurrentClusterInfo(clusterInfo: ClusterInfo) extends Response with ClusterManager.GetStatusResponse
  case class StartingInstance(instanceId: InstanceId) extends Response
  case object UnableToStartInstance extends Response
  case class UnknownInstance(instanceId: InstanceId) extends Response
  case class RktNodeAdded(nodeId: RktNodeId) extends Response

}

class RktClusterManager(
    clusterId: String,
    rktRunnerFactory: RktClusterManager.RktRunnerFactory,
    hostKeyChecker: RktClusterManager.HostKeyChecker)
    extends PersistentActor
    with ClusterManager
    with ActorLogging {
  import dit4c.scheduler.domain.rktclustermanager._
  import ClusterManager._
  import RktClusterManager._
  import akka.pattern.{ask, pipe}

  lazy val persistenceId = s"RktClusterManager-$clusterId"

  protected var state = ClusterInfo()

  protected case class PendingOperation(requester: ActorRef, op: Any)
  protected var operationsAwaitingInstanceWorkers =
    Map.empty[ActorRef, Promise[ActorRef]]

  override def receiveCommand = {
    case GetStatus =>
      sender ! CurrentClusterInfo(state)
    case AddRktNode(host, port, username, rktDir) =>
      val id = RktNode.newId
      node(id) forward RktNode.Initialize(host, port, username, rktDir)
    case GetRktNodeState(id) =>
      node(id) forward RktNode.GetState
    case RegisterRktNode(nodeId) =>
      persist(RktNodeRegistered(nodeId))(updateState)
      sender ! RktNodeAdded(nodeId)
    case ConfirmRktNodeKeys(id) =>
      node(id) forward RktNode.ConfirmKeys
    case Shutdown =>
      context.stop(self)
    case StartInstance(instanceId, image, portalUri) =>
      implicit val timeout = Timeout(1.minute)
      import context.dispatcher
      val requester = sender
      fInstance(instanceId)
        .flatMap { instance =>
          // Request start, wait for acknowledgement,
          (instance ? Instance.Initiate(image, portalUri)).collect {
            case Instance.Ack => StartingInstance(instanceId)
          }
        }
        .recover {
          case _ => UnableToStartInstance
        }
        .pipeTo(requester)
    case GetInstanceStatus(instanceId) =>
      implicit val timeout = Timeout(1.minute)
      import context.dispatcher
      val requester = sender
      fInstance(instanceId).andThen {
        case Success(ref) =>
          ref.tell(Instance.GetStatus, requester)
        case Failure(e) =>
          requester ! UnknownInstance(instanceId)
      }
    case InstanceEnvelope(instanceId, msg: Instance.Command) =>
      implicit val timeout = Timeout(1.minute)
      import context.dispatcher
      val requester = sender
      fInstance(instanceId).andThen {
        case Success(ref) =>
          ref.tell(msg, requester)
        case Failure(e) =>
          requester ! UnknownInstance(instanceId)
      }
    case msg: RktInstanceScheduler.Response =>
      operationsAwaitingInstanceWorkers.get(sender).foreach { p =>
        import RktInstanceScheduler._
        msg match {
          case WorkerFound(worker) =>
            p.success(worker)
          case NoWorkersAvailable =>
            p.failure(new Exception("No worker found"))
        }
        operationsAwaitingInstanceWorkers -= sender
      }

  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: DomainEvent => updateState(e)
  }

  protected def updateState(e: DomainEvent): Unit = e match {
    case RktNodeRegistered(nodeId, _) =>
      state = state.copy(nodeIds = state.nodeIds + nodeId)
  }

  protected def fInstance(
      instanceId: String)(implicit ec: ExecutionContext): Future[ActorRef] =
    context.child(s"instance-$instanceId") match {
      case None =>
        instanceWorker(instanceId).map { worker =>
          log.info(worker.toString)
          context.actorOf(
            Instance.props(
                instanceId,
                worker),
            s"instance-$instanceId")
        }
      case Some(ref) =>
        Future.successful(ref)
    }

  protected def instanceWorker(instanceId: String): Future[ActorRef] = {
    val p = Promise[ActorRef]()
    val instanceSchedulerRef =
      context.actorOf(
          RktInstanceScheduler.props(
              instanceId,
              state.nodeIds.map(node),
              1.minute),
          "instance-scheduler-"+instanceId)
    operationsAwaitingInstanceWorkers += (instanceSchedulerRef -> p)
    p.future
  }

  protected def node(nodeId: String): ActorRef =
    context.child(s"node-$nodeId").getOrElse {
      context.actorOf(
        RktNode.props(
            clusterId,
            nodeId,
            rktRunnerFactory,
            hostKeyChecker),
        s"node-$nodeId")
    }

}