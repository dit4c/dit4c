package dit4c.scheduler.domain

import akka.persistence.PersistentActor
import akka.actor.Props
import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import akka.persistence.fsm.PersistentFSM.FSMState
import scala.reflect._
import java.security.KeyPairGenerator
import scala.concurrent.Future
import java.security.PublicKey
import akka.actor.ActorRef
import java.time.Instant

object RktNode {

  type NodeId = String

  /**
   * 32-bit identifier as hexadecimal - good enough for cluster-local
   */
  def newId = Seq.fill(4)(Random.nextInt(255)).map(i => f"$i%x").mkString

  def props(
      fetchSshHostKey: (String, Int) => Future[RSAPublicKey]): Props =
        Props(classOf[RktNode], fetchSshHostKey)

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
      connectionDetails: ServerConnectionDetails,
      rktDir: String,
      readyToConnect: Boolean) extends Data

  trait Command
  case class Initialize(
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends Command
  case class FinishInitializing(
      init: Initialize,
      serverPublicKey: ServerPublicKey,
      replyTo: ActorRef)
  case object GetState extends Command
  case object ConfirmKeys extends Command

  /**
   * Note that domain events are the only persisted events. All the other FSM
   * "events" could be commands or something else.
   */
  trait DomainEvent extends BaseDomainEvent
  case class Initialized(
      connectionDetails: ServerConnectionDetails,
      rktDir: String,
      timestamp: Instant = Instant.now) extends DomainEvent
  case class KeysConfirmed(
      timestamp: Instant = Instant.now) extends DomainEvent

}

class RktNode(
    fetchSshHostKey: (String, Int) => Future[RSAPublicKey])
    extends PersistentFSM[RktNode.State, RktNode.Data, RktNode.DomainEvent] {
  import RktNode._

  lazy val persistenceId = self.path.name

  startWith(JustCreated, NoConfig)

  when(JustCreated) {
    case Event(GetState, data) =>
      stay replying data
    case Event(init: Initialize, _) =>
      import context.dispatcher
      val replyTo = sender
      fetchSshHostKey(init.host, init.port).onSuccess {
        case k =>
          log.debug(s"${init.host}:${init.port} has host key: $k")
          self ! FinishInitializing(init, ServerPublicKey(k), replyTo)
      }
      stay
    case Event(FinishInitializing(init, serverPublicKey, replyTo), _) =>
      val clientKeyPair: ClientKeyPair = createKeyPair
      val scd = ServerConnectionDetails(
          init.host, init.port, init.username, clientKeyPair, serverPublicKey)
      goto(PendingKeyConfirmation)
        .applying(Initialized(scd, init.rktDir))
        .andThen {
          case data =>
            context.parent ! ClusterAggregate.RegisterRktNode(replyTo)
        }
  }

  when(PendingKeyConfirmation) {
    case Event(GetState, data) =>
      stay replying data
    case Event(ConfirmKeys, data) =>
      goto(Active).applying(KeysConfirmed()).andThen {
        case data =>
          sender ! data
      }
  }

  when(Active) {
    case Event(GetState, data) =>
      stay replying data
    // TODO: Actual node functionality
  }


  def applyEvent(
      domainEvent: DomainEvent,
      dataBeforeEvent: Data): RktNode.Data = {
    domainEvent match {
      case Initialized(connectionDetails, rktDir, _) =>
        NodeConfig(connectionDetails, rktDir, false)
      case KeysConfirmed(_) =>
        dataBeforeEvent match {
          case c: NodeConfig => c.copy(readyToConnect = true)
          case other => other
        }
    }
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  private def createKeyPair: ClientKeyPair = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair
    ClientKeyPair(
        kp.getPublic.asInstanceOf[RSAPublicKey],
        kp.getPrivate.asInstanceOf[RSAPrivateKey])
  }

}