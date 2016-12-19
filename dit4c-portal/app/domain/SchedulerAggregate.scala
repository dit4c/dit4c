package domain

import scala.reflect._

import com.softwaremill.tagging._
import akka.actor.ActorLogging
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import domain.SchedulerAggregate._
import java.time.Instant
import pdi.jwt.JwtJson
import akka.actor.ActorRef
import dit4c.protobuf.scheduler.outbound.AllocatedInstanceKey
import java.security.spec.RSAPublicKeySpec
import domain.InstanceAggregate.AssociatePGPPublicKey
import domain.scheduler.DomainEvent
import scala.util.Success
import scala.util.Failure
import java.security.PublicKey
import akka.actor.Actor
import akka.actor.Props
import akka.util.Timeout
import scala.concurrent.duration._
import services.KeyRingSharder
import akka.persistence.PersistentActor
import utils.akka.ActorHelpers
import dit4c.common.KeyHelpers.PGPFingerprint

object SchedulerAggregate {

  sealed trait Command extends BaseCommand
  case class ClusterEnvelope(clusterId: String, payload: Any) extends Command
  case object Create extends Command
  case class VerifyJwt(token: String) extends Command
  case class RegisterSocket(socketActor: ActorRef) extends Command
  case class DeregisterSocket(socketActor: ActorRef) extends Command
  case class ReceiveSchedulerMessage(msg: dit4c.protobuf.scheduler.outbound.OutboundMessage) extends Command
  case class SendSchedulerMessage(msg: dit4c.protobuf.scheduler.inbound.InboundMessage) extends Command
  case class UpdateKeys(armoredPgpPublicKeyBlock: String) extends Command
  case object GetKeys extends Command
  case class GetAvailableClusters(
      accessTokenIds: Set[String]) extends Command

  sealed trait Response extends BaseResponse
  case object Ack extends Response
  sealed trait VerifyJwtResponse extends Response
  case object ValidJwt extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse
  sealed trait SendSchedulerMessageResponse extends Response
  case object MessageSent extends Response
  case object UnableToSendMessage extends Response
  sealed trait UpdateKeysResponse extends Response
  case object KeysUpdated extends Response
  case class KeysRejected(reason: String) extends Response
  sealed trait GetKeysResponse extends Response
  case class CurrentKeys(
      primaryKeyBlock: String,
      additionalKeyBlocks: List[String] = Nil) extends GetKeysResponse
  case object NoKeysAvailable extends GetKeysResponse
  trait GetAvailableClustersResponse extends Response
  case class AvailableClusters(
      clusters: List[UserAggregate.AvailableCluster]) extends GetAvailableClustersResponse

}

class SchedulerAggregate(
    imageServerConfig: ImageServerConfig,
    keyringSharder: ActorRef @@ KeyRingSharder.type)
    extends PersistentActor
    with ActorLogging
    with ActorHelpers {
  import BaseDomainEvent.now
  import domain.scheduler._
  import akka.pattern.{ask, pipe}
  import context.dispatcher
  implicit val timeout = Timeout(1.minute)

  lazy val schedulerId = self.path.name
  override lazy val persistenceId: String = "Scheduler-" + self.path.name

  lazy val clusterManager: ActorRef =
    context.actorOf(
        Props(classOf[ClusterManager], imageServerConfig),
        "clusters")

  var schedulerSocket: Option[ActorRef] = None

  override val receiveCommand: Receive = doesNotExist

  def doesNotExist: Receive = sealedReceive[Command] {
    case Create =>
      val requester = sender
      persist(Created(now))(updateState)
      sender ! Ack
    case GetKeys =>
      sender ! NoKeysAvailable
    case VerifyJwt(_) =>
      sender ! InvalidJwt("Unknown scheduler")
    case UpdateKeys(_) =>
      sender ! KeysRejected("Unknown scheduler")
    case _ =>
      // Ignore all other messages
  }

  def active: Receive = sealedReceive[Command] {
    case Create =>
      log.warning("Attempted to create existing scheduler")
    case msg: ClusterEnvelope =>
      clusterManager forward msg
    case UpdateKeys(keyBlock) =>
      import dit4c.common.KeyHelpers._
      KeyRingSharder.Envelope.forKeySubmission(keyBlock) match {
        case Left(msg) =>
          sender ! KeysRejected(msg)
        case Right(envelope) if envelope.fingerprint.string != schedulerId =>
          sender ! KeysRejected(
              "Master key fingerprint does not match scheduler ID")
        case Right(envelope) =>
          import akka.pattern.{ask, pipe}
          import context.dispatcher
          implicit val timeout = Timeout(1.minute)
          import KeyRingAggregate._
          (keyringSharder ? envelope)
            .collect {
              case KeySubmissionAccepted(_) => KeysUpdated
              case KeySubmissionRejected(r) => KeysRejected(r)
            }
            .pipeTo(sender)
      }
    case GetKeys =>
      import akka.pattern.{ask, pipe}
      import context.dispatcher
      implicit val timeout = Timeout(1.minute)
      val msg = KeyRingSharder.Envelope(
          PGPFingerprint(schedulerId),
          KeyRingAggregate.GetKeys)
      (keyringSharder ? msg)
        .collect {
          case KeyRingAggregate.NoKeysAvailable => NoKeysAvailable
          case KeyRingAggregate.CurrentKeyBlock(s) => CurrentKeys(s)
        }
        .pipeTo(sender)
    case VerifyJwt(token) =>
      import dit4c.common.KeyHelpers._
      val msg = KeyRingSharder.Envelope(
          PGPFingerprint(schedulerId),
          KeyRingAggregate.GetKeys)
      (keyringSharder ? msg)
        .collect {
          case KeyRingAggregate.NoKeysAvailable =>
            InvalidJwt("No keys available to validate")
          case KeyRingAggregate.CurrentKeyBlock(keyBlock) =>
            parseArmoredPublicKeyRing(keyBlock).right.get
              .authenticationKeys
              .flatMap(k => k.asJavaPublicKey.map((k.fingerprint, _))) match {
                case Nil =>
                  InvalidJwt("No keys available to validate")
                case keys =>
                  keys
                    .map { case (fingerprint, key) =>
                      JwtJson.decode(token, key) match {
                        case Success(_) => ValidJwt
                        case Failure(e) => InvalidJwt(s"$fingerprint: ${e.getMessage}")
                      }
                    }
                    .reduce[VerifyJwtResponse] {
                      case (InvalidJwt(a), InvalidJwt(b)) =>
                        InvalidJwt(s"$a\n$b")
                      case _ => ValidJwt
                    }
              }
        }
        .pipeTo(sender)
    case RegisterSocket(ref) =>
      schedulerSocket = Some(ref)
      log.info(sender.toString)
      log.info(s"Registered socket: ${ref.path.toString}")
    case DeregisterSocket(ref) =>
      if (Some(ref) == schedulerSocket) {
        schedulerSocket = None
        log.info(s"Deregistered socket: ${ref.path.toString}")
      } else {
        log.debug(s"Ignored deregister: ${ref.path.toString}")
      }
    case ReceiveSchedulerMessage(msg) =>
      import dit4c.protobuf.scheduler.outbound.OutboundMessage.Payload
      import services.InstanceSharder
      import domain.InstanceAggregate.AssociatePGPPublicKey
      msg.payload match {
        case Payload.Empty => // Do nothing
        case Payload.InstanceStateUpdate(msg) =>
          val envelope = InstanceSharder.Envelope(msg.instanceId, msg)
          context.system.eventStream.publish(envelope)
        case Payload.AllocatedInstanceKey(msg) =>
          val envelope = InstanceSharder.Envelope(msg.instanceId, AssociatePGPPublicKey(msg.pgpPublicKeyBlock))
          context.system.eventStream.publish(envelope)
        case Payload.ClusterStateUpdate(msg) =>
          import dit4c.protobuf.scheduler.outbound.ClusterStateUpdate.ClusterState._
          val timestamp = msg.timestamp match {
            case None => Instant.now
            case Some(ts) =>
              Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong)
          }
          val update =
            Cluster.UpdateInfo(
              msg.state match {
                case ACTIVE => Cluster.Active(msg.displayName, msg.supportsSave)
                case INACTIVE => Cluster.Inactive(msg.displayName)
                case Unrecognized(v) =>
                  log.error(s"Unknown cluster state! Converting $v to inactive.")
                  Cluster.Inactive(msg.displayName)
              },
              timestamp)
          clusterManager ! ClusterEnvelope(msg.clusterId, update)
      }
    case SendSchedulerMessage(msg) =>
      val response: Response = schedulerSocket match {
        case Some(ref) =>
          ref ! msg
          SchedulerAggregate.Ack
        case None =>
          log.warning(s"Unable to send: $msg")
          SchedulerAggregate.UnableToSendMessage
      }
      if (sender != context.system.deadLetters) {
        sender ! response
      }
    case GetAvailableClusters(accessPassIds) =>
      import akka.pattern.{ask, pipe}
      import context.dispatcher
      implicit val timeout = Timeout(10.seconds)
      accessPassIds
        // Get all the valid passes
        .map { accessPassId =>
          import AccessPass._
          val cmd = AccessPassManager.Envelope(accessPassId, GetDecodedPass)
          (accessPassManagerRef ? cmd)
            .map[List[ValidPass]] {
              case vp: ValidPass => List(vp)
              case _ => Nil
            }
            .recover[List[ValidPass]] {
              case e =>
                log.error(e,
                    s"Scheduler $schedulerId failed to get "+
                    s"access pass details for $accessPassId")
                Nil
            }
        }
        // Collapse to single future
        .reduce { (aF, bF) =>
          for (a <- aF; b <- bF) yield a ++ b
        }
        .map { validPasses =>
          // Collect clusters out of passes, and calculate effective expiry
          validPasses
            .foldRight(Map.empty[String, List[AccessPass.ValidPass]]) { (vp, m) =>
              m ++ vp.pass.clusterIds.map { id =>
                // Append ValidPass to the list for this clusterId
                (id, m.getOrElse(id, Nil) :+ vp)
              }
            }.toList.map { case (clusterId, passes) =>
              val longestPass =
                passes.maxBy(_.expires.getOrElse(Instant.MAX))
              UserAggregate.AvailableCluster(
                  schedulerId,
                  clusterId,
                  longestPass.expires)
            }
        }
        .map(AvailableClusters(_))
        .pipeTo(sender)
  } orElse sealedReceive[AccessPassManager.Command] {
    case cmd: AccessPassManager.Command =>
      accessPassManagerRef forward cmd
  }

  override val receiveRecover = sealedReceive[DomainEvent](updateState _)

  def updateState(e: DomainEvent): Unit = e match {
    case Created(_) => context.become(active)
  }

  def accessPassManagerRef: ActorRef = {
    val name = "access-passes"
    context.child(name).getOrElse {
      context.actorOf(Props(classOf[AccessPassManager]), name)
    }
  }

}