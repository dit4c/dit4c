package dit4c.scheduler.domain

import akka.persistence.PersistentActor
import akka.actor.Props
import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import akka.persistence.fsm.PersistentFSM.FSMState
import scala.reflect._
import java.nio.file.Path
import java.nio.file.Paths

object RktNode {

  type NodeId = String

  /**
   * 32-bit identifier as hexadecimal - good enough for cluster-local
   */
  def newId = Seq.fill(4)(Random.nextInt(255)).map(i => f"$i%x").mkString

  def props(pId: String): Props = Props(classOf[RktNode], pId)

  case class ClientKeyPair(public: RSAPublicKey, `private`: RSAPrivateKey)
  case class ServerPublicKey(public: RSAPublicKey)

  case class ServerConnectionDetails(
      host: String,
      port: Int,
      username: String,
      clientKey: ClientKeyPair,
      serverKey: ServerPublicKey)

  trait State extends FSMState
  case object JustCreated extends State {
    override val identifier = "Just Created"
  }
  case object PendingKeyConfirmation extends State {
    override val identifier = "Pending Key Confirmation"
  }
  case object Active extends State {
    override val identifier = "Active"
  }

  trait Data
  case object NoConfig extends Data
  case class NodeConfig(
      id: NodeId,
      connectionDetails: ServerConnectionDetails,
      rktDir: Path,
      readyToConnect: Boolean) extends Data

  trait Command
  case class Initialize(
      id: NodeId,
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends Command
  case object GetState extends Command
  case object ConfirmKeys extends Command

  /**
   * Note that domain events are the only persisted events. All the other FSM
   * "events" could be commands or something else.
   */
  trait DomainEvent
  case class Initialized(
      id: String,
      connectionDetails: ServerConnectionDetails,
      rktDir: Path) extends DomainEvent
  case object KeysConfirmed extends DomainEvent


}

class RktNode(val persistenceId: String)
    extends PersistentFSM[RktNode.State, RktNode.Data, RktNode.DomainEvent] {
  import RktNode._

  startWith(JustCreated, NoConfig)

  when(JustCreated) {
    case Event(GetState, data) =>
      stay replying data
    case Event(Initialize(id, host, port, username, rktDir), _) =>
      // TODO: write code to
      // * Create new client keypair
      // * Attempt a connection to the server, and retrieve host key
      val clientKeyPair: ClientKeyPair = ???
      val serverPublicKey: ServerPublicKey = ???
      val dir = Paths.get(rktDir)
      val scd = ServerConnectionDetails(
          host, port, username, clientKeyPair, serverPublicKey)
      goto(PendingKeyConfirmation).applying(Initialized(id, scd, dir)).andThen {
        case data =>
          context.parent ! ClusterAggregate.RegisterRktNode(id)
          sender ! data
      }
  }

  when(PendingKeyConfirmation) {
    case Event(GetState, data) =>
      stay replying data
    case Event(ConfirmKeys, data) =>
      goto(Active).applying(KeysConfirmed).andThen {
        case data =>
          sender ! data
      }
  }

  when(Active) {
    case Event(GetState, data) =>
      stay replying data
    // TODO: Actual node functionality
  }


  def applyEvent(domainEvent: DomainEvent, dataBeforeEvent: Data): RktNode.Data = {
    domainEvent match {
      case Initialized(id, connectionDetails, rktDir) =>
        NodeConfig(id, connectionDetails, rktDir, false)
      case KeysConfirmed =>
        dataBeforeEvent match {
          case c: NodeConfig => c.copy(readyToConnect = true)
          case other => other
        }
    }
  }

  override def domainEventClassTag: ClassTag[DomainEvent] = classTag[DomainEvent]

}