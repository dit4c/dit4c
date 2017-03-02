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
import akka.pattern.ask

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
      instanceNodeMappings: Map[InstanceId, RktNodeId] = Map.empty,
      nodeIds: Set[RktNodeId] = Set.empty)

  trait Command extends ClusterManager.Command
  case object Shutdown extends Command
  case class AddRktNode(
      nodeId: RktNodeId,
      host: String,
      port: Int,
      username: String,
      sshHostKeyFingerprints: Seq[String],
      rktDir: String) extends Command
  protected[domain] case class GetRktNodeState(nodeId: RktNodeId) extends Command
  case class CoolDownRktNodes(sshHostKeyFingerprints: Seq[String]) extends Command
  case class DecommissionRktNodes(sshHostKeyFingerprints: Seq[String]) extends Command
  case class RegisterRktNode(requester: ActorRef) extends Command
  case class StartInstance(
      instanceId: String, image: String, portalUri: String) extends Command
  case class GetInstanceStatus(id: Instance.Id) extends Command
  case class InstanceEnvelope(id: Instance.Id, msg: Instance.Command) extends Command

  trait Response extends ClusterManager.Response
  case class CurrentClusterInfo(clusterInfo: ClusterInfo) extends Response with ClusterManager.GetStatusResponse
  case class StartingInstance(instanceId: InstanceId) extends Response
  case class UnableToStartInstance(instanceId: InstanceId, msg: String) extends Response
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
  import BaseDomainEvent._
  import dit4c.scheduler.domain.rktclustermanager._
  import ClusterManager._
  import RktClusterManager._
  import akka.pattern.{ask, pipe}

  lazy val persistenceId = s"RktClusterManager-$clusterId"

  protected var state = ClusterInfo()

  protected case class PendingOperation(requester: ActorRef, op: Any)
  protected var operationsAwaitingInstanceWorkers =
    Map.empty[ActorRef, PendingOperation]

  override def receiveCommand = {
    case GetStatus =>
      sender ! CurrentClusterInfo(state)
    case AddRktNode(id, host, port, username, fingerprints, rktDir) =>
      processNodeCommand(id,
          RktNode.Initialize(host, port, username, fingerprints, rktDir))
    case GetRktNodeState(id) =>
      processNodeCommand(id, RktNode.GetState)
    case CoolDownRktNodes(fingerprints) =>
      import context.dispatcher
      implicit val timeout = Timeout(1.minute)
      val hasMatch = hasMatchingFingerprint(fingerprints) _
      state.nodeIds
        .map(id => (id, getNodeActor(id)))
        .foreach { case (id, ref) =>
          (ref ? RktNode.GetState)
            .map(_.asInstanceOf[RktNode.GetStateResponse])
            .onSuccess {
              case RktNode.Exists(c) if hasMatch(c.connectionDetails.serverKey) =>
                log.info(s"Cooling down node $id - will accept no further instances")
                ref ! RktNode.BecomeInactive
            }
        }
    case DecommissionRktNodes(fingerprints) =>
      import dit4c.common.KeyHelpers._
      import context.dispatcher
      implicit val timeout = Timeout(1.minute)
      val hasMatch = hasMatchingFingerprint(fingerprints) _
      state.nodeIds
        .map(id => (id, getNodeActor(id)))
        .foreach { case (id, ref) =>
          (ref ? RktNode.GetState)
            .map(_.asInstanceOf[RktNode.GetStateResponse])
            .collect {
              case RktNode.Exists(c) if hasMatch(c.connectionDetails.serverKey) =>
                log.info(s"Decommissioning node $id")
                ref ! RktNode.Decommission
                ref
            }
            .flatMap(_ ? RktNode.GetState)
            .onSuccess {
              case RktNode.DoesNotExist =>
                log.info(s"Updating state for all instances on decommissioned node $id")
                state.instanceNodeMappings.collect {
                  case (instanceId, `id`) =>
                    (context.self ? GetInstanceStatus(instanceId)).foreach { _ =>
                      log.debug(s"Triggered state update for $instanceId")
                    }
                }
            }
        }
    case RegisterRktNode(requester) =>
      sender.path.name match {
        case RktNodePersistenceId(id) =>
          persist(RktNodeRegistered(id, now))(updateState)
          requester ! RktNodeAdded(id)
      }
    case Shutdown =>
      context.stop(self)
    case op: StartInstance =>
      log.info("Requesting instance worker from nodes")
      val instanceSchedulerRef =
        context.actorOf(Props(classOf[RktInstanceScheduler],
          op.instanceId,
          state.nodeIds.map(id => (id, getNodeActor(id))).toMap,
          1.minute,
          false),
          "instance-scheduler-"+op.instanceId)
      operationsAwaitingInstanceWorkers +=
        (instanceSchedulerRef -> PendingOperation(sender,op))
    case op @ GetInstanceStatus(instanceId) =>
      context.child(InstancePersistenceId(instanceId)) match {
        case Some(ref) => ref forward Instance.GetStatus
        case None =>
          if (state.instanceNodeMappings.contains(instanceId)) {
            val instanceSchedulerRef =
              context.child("instance-scheduler-"+instanceId).getOrElse {
                context.actorOf(Props(classOf[RktInstanceScheduler],
                  instanceId,
                  state.instanceNodeMappings.get(instanceId).map(id => (id, getNodeActor(id))).toMap,
                  1.minute,
                  true),
                  "instance-scheduler-"+instanceId)
              }
            operationsAwaitingInstanceWorkers +=
              (instanceSchedulerRef -> PendingOperation(sender,op))
          } else {
            sender ! UnknownInstance(instanceId)
          }
      }
    case InstanceEnvelope(instanceId, msg: Instance.Command) =>
      context.child(InstancePersistenceId(instanceId)) match {
        case Some(ref) =>
          implicit val timeout = Timeout(10.seconds)
          import context.dispatcher
          ref forward msg
        case None => sender ! UnknownInstance(instanceId)
      }

    case msg: RktInstanceScheduler.Response =>
      operationsAwaitingInstanceWorkers.get(sender).foreach {
        case PendingOperation(requester, StartInstance(instanceId, image, portalUri)) =>
          import RktInstanceScheduler._
          msg match {
            case WorkerFound(nodeId, worker) =>
              implicit val timeout = Timeout(10.seconds)
              import context.dispatcher
              persist(InstanceAssignedToNode(instanceId, nodeId, now))(updateState)
              val instance = context.actorOf(
                  Instance.props(worker),
                  InstancePersistenceId(instanceId))
              // Request start, wait for acknowledgement,
              (instance ? Instance.Initiate(instanceId, image, portalUri))
                .collect { case Instance.Ack => StartingInstance(instanceId) }
                .pipeTo(requester)
            case NoWorkersAvailable =>
              requester ! UnableToStartInstance(instanceId, "No compute nodes available")
          }
        case PendingOperation(requester, GetInstanceStatus(instanceId)) =>
          import RktInstanceScheduler._
          msg match {
            case WorkerFound(nodeId, worker) =>
              implicit val timeout = Timeout(10.seconds)
              import context.dispatcher
              val instance = context.actorOf(
                  Instance.props(worker),
                  InstancePersistenceId(instanceId))
              instance.tell(Instance.GetStatus, requester)
            case NoWorkersAvailable =>
              requester ! UnknownInstance(instanceId)
          }
      }
      operationsAwaitingInstanceWorkers -= sender

  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: DomainEvent => updateState(e)
  }

  protected def updateState(e: DomainEvent): Unit = e match {
    case RktNodeRegistered(nodeId, _) =>
      state = state.copy(nodeIds = state.nodeIds + nodeId)
    case InstanceAssignedToNode(instanceId, nodeId, _) =>
      state = state.copy(instanceNodeMappings =
        state.instanceNodeMappings + (instanceId -> nodeId))

  }

  object RktNodePersistenceId extends ChildPersistenceId("RktNode")

  def processNodeCommand(nodeId: String, command: Any) =
    getNodeActor(nodeId) forward command

  protected def getNodeActor(nodeId: String): ActorRef =
    context.child(RktNodePersistenceId(nodeId))
      .getOrElse(createNodeActor(nodeId))

  protected def createNodeActor(nodeId: String): ActorRef = {
    val node = context.actorOf(
        RktNode.props(rktRunnerFactory, hostKeyChecker),
        RktNodePersistenceId(nodeId))
    context.watch(node)
    node
  }


  private def hasMatchingFingerprint(fingerprints: Seq[String])(serverKey: RktNode.ServerPublicKey) = {
    import dit4c.common.KeyHelpers._
    fingerprints.contains(serverKey.public.ssh.fingerprint("SHA-256")) ||
    fingerprints.contains(serverKey.public.ssh.fingerprint("MD5"))
  }

}