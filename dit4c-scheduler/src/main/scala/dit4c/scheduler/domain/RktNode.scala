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
import com.google.protobuf.timestamp.Timestamp
import dit4c.scheduler.domain.rktnode.DomainEvent
import RktNode.{State, Data}

object RktNode {
  import BaseDomainEvent.now

  type NodeId = String

  /**
   * 32-bit identifier as hexadecimal - good enough for cluster-local
   */
  def newId = Seq.fill(4)(Random.nextInt(255)).map(i => f"$i%x").mkString

  def props(
      rktRunnerFactory: RktClusterManager.RktRunnerFactory,
      fetchSshHostKey: RktClusterManager.HostKeyChecker): Props =
        Props(classOf[RktNode], rktRunnerFactory, fetchSshHostKey)

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

  trait Command extends BaseCommand
  case object GetState extends Command
  case object RequestInstanceWorker extends Command
  case object RequireInstanceWorker extends Command
  case class Initialize(
      host: String,
      port: Int,
      username: String,
      rktDir: String) extends Command
  case class FinishInitializing(
      init: Initialize,
      serverPublicKey: ServerPublicKey,
      replyTo: ActorRef) extends Command
  case object ConfirmKeys extends Command

  trait Response extends BaseResponse
  trait GetStateResponse extends Response
  case object DoesNotExist extends GetStateResponse
  case class Exists(nodeConfig: NodeConfig) extends GetStateResponse
  case class ConfirmKeysResponse(nodeConfig: NodeConfig) extends Response
  trait InstanceWorkerResponse extends Response
  case class WorkerCreated(worker: ActorRef) extends InstanceWorkerResponse
  case class UnableToProvideWorker(msg: String) extends InstanceWorkerResponse

}

class RktNode(
    createRunner: RktClusterManager.RktRunnerFactory,
    fetchSshHostKey: RktClusterManager.HostKeyChecker)
    extends PersistentFSM[State, Data, DomainEvent] {
  import dit4c.scheduler.domain.rktnode._
  import RktNode._

  lazy val persistenceId = self.path.name

  startWith(JustCreated, NoConfig)

  when(JustCreated) {
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
      import dit4c.common.KeyHelpers._
      val clientKeyPair: ClientKeyPair = createKeyPair
      goto(PendingKeyConfirmation)
        .applying(Initialized(
            init.host, init.port, init.username,
            clientKeyPair.`private`.pkcs8.pem,
            clientKeyPair.public.pkcs8.pem,
            serverPublicKey.public.pkcs8.pem,
            init.rktDir))
        .andThen {
          case data =>
            context.parent ! RktClusterManager.RegisterRktNode(replyTo)
        }
  }

  when(PendingKeyConfirmation) {
    case Event(ConfirmKeys, data) =>
      goto(Active).applying(KeysConfirmed()).andThen {
        case data: NodeConfig =>
          sender ! ConfirmKeysResponse(data)
      }
  }

  when(Active) {
    case Event(
        RequestInstanceWorker | RequireInstanceWorker,
        NodeConfig(connectionDetails, rktDir, _)) =>
      val runner = createRunner(connectionDetails, rktDir)
      val worker = context.actorOf(
          Props(classOf[RktInstanceWorker], runner),
          "instance-worker-"+Random.alphanumeric.take(20).mkString)
      stay replying WorkerCreated(worker)
    case Event(ConfirmKeys, data: NodeConfig) =>
      stay replying ConfirmKeysResponse(data)
  }

  whenUnhandled {
    case Event(GetState, NoConfig) =>
      stay replying DoesNotExist
    case Event(GetState, c: NodeConfig) =>
      stay replying Exists(c)
    case Event(RequestInstanceWorker, _) =>
      stay replying UnableToProvideWorker("Node is not currently active")
  }


  def applyEvent(
      domainEvent: DomainEvent,
      dataBeforeEvent: Data): RktNode.Data = {
    domainEvent match {
      case Initialized(host, port, username, clientPrivateKeyPKCS8PEM, clientPublicKeyPKCS8PEM, serverPublicKeyPKCS8PEM, rktDir, _) =>
        import dit4c.common.KeyHelpers._
        NodeConfig(
          ServerConnectionDetails(
            host, port, username,
            ClientKeyPair(
              parsePkcs8PemPublicKey(clientPublicKeyPKCS8PEM)
                .right.get.asInstanceOf[RSAPublicKey],
              parsePkcs8PemPrivateKey(clientPrivateKeyPKCS8PEM)
                .right.get.asInstanceOf[RSAPrivateKey]),
            ServerPublicKey(
              parsePkcs8PemPublicKey(serverPublicKeyPKCS8PEM)
                .right.get.asInstanceOf[RSAPublicKey])
          ),
          rktDir, false)
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