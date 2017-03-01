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
import akka.actor.PoisonPill
import akka.actor.Cancellable
import dit4c.scheduler.runner.RktRunner

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
      readyToConnect: Boolean) extends Data {
    private var rktRunner: Option[RktRunner] = None
    def runner(createRunner: RktClusterManager.RktRunnerFactory) =
      rktRunner.getOrElse {
        rktRunner = Some(createRunner(connectionDetails, rktDir))
        rktRunner.get
      }
  }

  sealed trait Command extends BaseCommand
  case object GetState extends Command
  case class RequestInstanceWorker(instanceId: String) extends Command
  case class RequireInstanceWorker(instanceId: String) extends Command
  case class Initialize(
      host: String,
      port: Int,
      username: String,
      sshHostKeyFingerprints: Seq[String],
      rktDir: String) extends Command
  case class FinishInitializing(
      init: Initialize,
      serverPublicKey: ServerPublicKey,
      replyTo: ActorRef) extends Command

  sealed trait Response extends BaseResponse
  sealed trait GetStateResponse extends Response
  case object DoesNotExist extends GetStateResponse
  case class Exists(nodeConfig: NodeConfig) extends GetStateResponse
  sealed trait InstanceWorkerResponse extends Response
  case class WorkerCreated(worker: ActorRef) extends InstanceWorkerResponse
  case class UnableToProvideWorker(msg: String) extends InstanceWorkerResponse

}

class RktNode(
    createRunner: RktClusterManager.RktRunnerFactory,
    fetchSshHostKey: RktClusterManager.HostKeyChecker)
    extends PersistentFSM[State, Data, DomainEvent] {
  import dit4c.scheduler.domain.rktnode._
  import RktNode._
  import scala.concurrent.duration._

  lazy val persistenceId = self.path.name

  private case object Tick extends BaseCommand
  private var ticker: Option[Cancellable] = None

  override def preStart = {
    import context.dispatcher
    ticker = Some(context.system.scheduler.schedule(5.seconds, 5.second, context.self, Tick))
  }

  override def postStop = {
    ticker.foreach(_.cancel)
  }

  var monitoredInstanceIds: Set[String] = Set.empty

  startWith(JustCreated, NoConfig)

  when(JustCreated) {
    case Event(init: Initialize, _) =>
      import dit4c.common.KeyHelpers._
      import context.dispatcher
      val replyTo = sender
      def hasMatchWithFingerprint(k: RSAPublicKey) =
        init.sshHostKeyFingerprints.contains(k.ssh.fingerprint("SHA-256")) ||
        init.sshHostKeyFingerprints.contains(k.ssh.fingerprint("MD5"))
      fetchSshHostKey(init.host, init.port).onSuccess {
        case k if hasMatchWithFingerprint(k) =>
          log.debug(s"${init.host}:${init.port} has host key: $k")
          self ! FinishInitializing(init, ServerPublicKey(k), replyTo)
        case k =>
          log.error(s"${self.path.name} host key does not match provided fingerprints! Aborting initialization.")
      }
      stay
    case Event(FinishInitializing(init, serverPublicKey, replyTo), _) =>
      import dit4c.common.KeyHelpers._
      goto(Active)
        .applying(Initialized(
            init.host, init.port, init.username,
            serverPublicKey.public.pkcs8.pem,
            init.rktDir))
        .andThen {
          case data =>
            context.parent ! RktClusterManager.RegisterRktNode(replyTo)
        }
  }

  /* Deprecated state */
  when(PendingKeyConfirmation, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, _) =>
      goto(JustCreated)
  }

  when(Active) {
    case Event(init: Initialize, _) =>
      log.info(s"Ignoring $init - already initialized")
      stay
    case Event(
        RequestInstanceWorker(instanceId),
        NodeConfig(connectionDetails, rktDir, _)) =>
      import scala.util._
      import context.dispatcher
      val requester = sender
      // Check node is up
      fetchSshHostKey(connectionDetails.host, connectionDetails.port).onComplete {
        case Success(_) =>
          val runner = createRunner(connectionDetails, rktDir)
          val worker = context.actorOf(
            Props(classOf[RktInstanceWorker], instanceId, runner),
            instanceWorkerActorName(instanceId))
          monitoredInstanceIds += instanceId
          requester ! WorkerCreated(worker)
        case Failure(e) =>
          log.error(s"${self.path} - ${e.getMessage}")
          // Inform requester
          requester ! UnableToProvideWorker("Node is unavailable")
      }
      stay
    case Event(
        RequireInstanceWorker(instanceId),
        NodeConfig(connectionDetails, rktDir, _)) =>
      val runner = createRunner(connectionDetails, rktDir)
      val worker = context.actorOf(
          Props(classOf[RktInstanceWorker], instanceId, runner),
          instanceWorkerActorName(instanceId))
      monitoredInstanceIds += instanceId
      stay replying WorkerCreated(worker)
    case Event(Tick, nc @ NodeConfig(connectionDetails, rktDir, _)) =>
      import context.dispatcher
      // Resolve workers to refs
      val instanceWorkers: Map[String, ActorRef] =
        (for {
          instanceId <- monitoredInstanceIds
          ref <- context.child(instanceWorkerActorName(instanceId))
        } yield (instanceId, ref)).toMap
      // Update monitoring to remove missing workers
      monitoredInstanceIds = instanceWorkers.keySet
      // Get current states
      nc.runner(createRunner).resolveStates(monitoredInstanceIds).foreach { states =>
        val ts = Instant.now
        // Tell workers
        for {
          (instanceId, state) <- states
          ref <- instanceWorkers.get(instanceId)
        } yield {
          ref ! RktInstanceWorker.CurrentInstanceState(state, ts)
        }
      }
      stay
  }

  whenUnhandled {
    case Event(GetState, NoConfig) =>
      stay replying DoesNotExist
    case Event(GetState, c: NodeConfig) =>
      stay replying Exists(c)
    case Event(RequestInstanceWorker(instanceId), _) =>
      stay replying UnableToProvideWorker("Node is not currently active")
    case Event(Tick, _) =>
      // Do nothing
      stay
  }


  def applyEvent(
      domainEvent: DomainEvent,
      dataBeforeEvent: Data): RktNode.Data = {
    domainEvent match {
      case Initialized(host, port, username, serverPublicKeyPKCS8PEM, rktDir, _) =>
        import dit4c.common.KeyHelpers._
        val hostKey = parsePkcs8PemPublicKey(serverPublicKeyPKCS8PEM)
                .right.get.asInstanceOf[RSAPublicKey]
        log.info(s"${self.path} ${hostKey.ssh.authorizedKeys}")
        NodeConfig(
          ServerConnectionDetails(
            host, port, username,
            ServerPublicKey(hostKey)
          ),
          rktDir, false)
      case KeysConfirmed(_) =>
        dataBeforeEvent match {
          case c: NodeConfig => c.copy(readyToConnect = true)
          case other => other
        }
    }
  }

  private def instanceWorkerActorName(instanceId: String) =
    "instance-worker-"+instanceId

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]
}