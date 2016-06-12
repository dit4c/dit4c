package dit4c.scheduler.domain

import akka.actor._
import akka.persistence._
import scala.util.Random
import scala.concurrent.Future
import java.security.interfaces.RSAPublicKey
import dit4c.scheduler.ssh.RemoteShell

object ClusterAggregate {

  type ClusterId = String
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
  case class RegisterRktNode(nodeId: RktNodeId) extends RktClusterCommand

  trait Event
  case class Initialized(cluster: Cluster) extends Event
  trait RktEvent extends Event
  case class RktNodeRegistered(nodeId: String) extends RktEvent

  trait Response
  case object AlreadyInitialized extends Response

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

  val rktCluster: Receive = commonBehaviour orElse {
    case AddRktNode(host, port, username, rktDir) =>
      val id = RktNode.newId
      Rkt.processNodeCommand(id,
          RktNode.Initialize(id, host, port, username, rktDir))
    case GetRktNodeState(id) =>
      Rkt.processNodeCommand(id, RktNode.GetState)
    case RegisterRktNode(id) =>
      persist(RktNodeRegistered(id)) { e: Event =>
        updateState(e)
      }
    case ConfirmRktNodeKeys(id) =>
      Rkt.processNodeCommand(id, RktNode.ConfirmKeys)
  }

  override def receiveRecover: PartialFunction[Any,Unit] = {
    case e: Event => updateState(e)
  }

  protected def updateState(e: Event): Unit = e match {
    case Initialized(newState: RktCluster) =>
      context.become(rktCluster)
      this.state = newState
    case RktNodeRegistered(nodeId: RktNodeId) =>
      state match {
        case cluster: RktCluster =>
          state = cluster.copy(nodeIds = cluster.nodeIds + nodeId)
      }
  }

  object Rkt {

    def processNodeCommand(nodeId: String, command: RktNode.Command) = {
      val maybeChild = context.child(nodePersistenceId(nodeId))
      maybeChild match {
        case Some(child) =>
          child forward command
        case None =>
          val child = createNodeActor(nodePersistenceId(nodeId))
          child forward command
      }
    }

    protected def createNodeActor(nodePersistenceId: String): ActorRef = {
      val node = context.actorOf(RktNode.props(nodePersistenceId, fetchSshHostKey))
      context.watch(node)
      node
    }

    protected def nodePersistenceId(nodeId: String) =
      Seq(persistenceId, nodeId).mkString("-")

  }



}