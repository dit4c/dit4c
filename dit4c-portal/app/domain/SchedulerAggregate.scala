package domain

import scala.reflect._

import akka.actor.ActorLogging
import akka.persistence.fsm.LoggingPersistentFSM
import akka.persistence.fsm.PersistentFSM
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

object SchedulerAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Active extends State

  sealed trait Data
  case object NoData extends Data
  case class SchedulerInfo(keyBlock: Option[String]) extends Data

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

}

class SchedulerAggregate(
    imageServerConfig: ImageServerConfig)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import BaseDomainEvent.now
  import domain.scheduler._
  implicit val m: Materializer = ActorMaterializer()

  lazy val schedulerId = self.path.name
  override lazy val persistenceId: String = "Scheduler-" + self.path.name

  lazy val clusterManager: ActorRef =
    context.actorOf(
        Props(classOf[ClusterManager], imageServerConfig),
        "clusters")

  var schedulerSocket: Option[ActorRef] = None

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(Create, _) =>
      val requester = sender
      goto(Active).applying(Created(now)).andThen { _ =>
        requester ! Ack
      }
    case Event(GetKeys, _) =>
      stay replying NoKeysAvailable
    case Event(VerifyJwt(_), _) =>
      stay replying InvalidJwt("Unknown scheduler")
    case Event(UpdateKeys(_), _) =>
      stay replying KeysRejected("Unknown scheduler")
  }

  when(Active) {
    case Event(msg: ClusterEnvelope, _) =>
      clusterManager forward msg
      stay
    case Event(UpdateKeys(keyBlock), SchedulerInfo(possibleKeyBlock)) =>
      import dit4c.common.KeyHelpers._
      parseArmoredPublicKeyRing(keyBlock) match {
        case Left(msg) =>
          stay replying KeysRejected(msg)
        case Right(kr) if kr.getPublicKey.fingerprint != schedulerId =>
          stay replying KeysRejected(
              "Master key fingerprint does not match scheduler ID")
        case Right(kr) if Some(keyBlock) == possibleKeyBlock =>
          stay replying KeysUpdated
        case Right(kr) =>
          // TODO: make this more secure
          stay applying UpdatedKeys(keyBlock, now) replying KeysUpdated
      }
    case Event(GetKeys, SchedulerInfo(None)) =>
      stay replying NoKeysAvailable
    case Event(GetKeys, SchedulerInfo(Some(keyBlock))) =>
      stay replying CurrentKeys(keyBlock)
    case Event(VerifyJwt(token), SchedulerInfo(None)) =>
      stay replying InvalidJwt("No keys available to validate")
    case Event(VerifyJwt(token), SchedulerInfo(Some(keyBlock))) =>
      import dit4c.common.KeyHelpers._
      stay replying {
        parseArmoredPublicKeyRing(keyBlock).right.get
          .authenticationKeys
          .flatMap(k => k.asJavaPublicKey.map((k.fingerprint, _))) match {
            case Nil =>
              stay replying InvalidJwt("No keys available to validate")
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
                    InvalidJwt("a\nb")
                  case _ => ValidJwt
                }
          }
      }
    case Event(RegisterSocket(ref), _) =>
      schedulerSocket = Some(ref)
      log.info(sender.toString)
      log.info(s"Registered socket: ${ref.path.toString}")
      stay
    case Event(DeregisterSocket(ref), _) =>
      if (Some(ref) == schedulerSocket) {
        schedulerSocket = None
        log.info(s"Deregistered socket: ${ref.path.toString}")
      } else {
        log.debug(s"Ignored deregister: ${ref.path.toString}")
      }
      stay
    case Event(ReceiveSchedulerMessage(msg), _) =>
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
      }
      stay
    case Event(SendSchedulerMessage(msg), _) =>
      val response: Response = schedulerSocket match {
        case Some(ref) =>
          ref ! msg
          SchedulerAggregate.Ack
        case None =>
          log.warning(s"Unable to send: $msg")
          SchedulerAggregate.UnableToSendMessage
      }
      if (sender == context.system.deadLetters) stay
      else {
        stay replying response
      }
  }

  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = domainEvent match {
    case Created(_) =>
      SchedulerInfo(None)
    case UpdatedKeys(keyBlock, _) => currentData match {
      case info: SchedulerInfo =>
        info.copy(Some(keyBlock))
      case data => unknownStateDataCombo(domainEvent, data)
    }
  }

  private def unknownStateDataCombo(e: DomainEvent, d: Data) =
    throw new Exception(s"Unknown event/data combination: $e / $d")

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]


}