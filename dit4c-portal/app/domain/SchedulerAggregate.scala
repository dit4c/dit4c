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

object SchedulerAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Active extends State

  sealed trait Data
  case object NoData extends Data
  case object SchedulerInfo extends Data

  sealed trait Command extends BaseCommand
  case object Create extends Command
  case class VerifyJwt(token: String) extends Command
  case class RegisterSocket(socketActor: ActorRef) extends Command
  case class DeregisterSocket(socketActor: ActorRef) extends Command
  case class ReceiveSchedulerMessage(msg: dit4c.protobuf.scheduler.outbound.OutboundMessage) extends Command
  case class SendSchedulerMessage(msg: dit4c.protobuf.scheduler.inbound.InboundMessage) extends Command

  sealed trait Response extends BaseResponse
  case object Ack extends Response
  sealed trait VerifyJwtResponse extends Response
  case object ValidJwt extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse
  sealed trait SendSchedulerMessageResponse extends Response
  case object MessageSent extends Response
  case object UnableToSendMessage extends Response

}

class SchedulerAggregate()
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import BaseDomainEvent.now
  import domain.scheduler._
  implicit val m: Materializer = ActorMaterializer()

  lazy val schedulerId = self.path.name
  override lazy val persistenceId: String = "Scheduler-" + self.path.name

  var schedulerSocket: Option[ActorRef] = None

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(Create, _) =>
      val requester = sender
      goto(Active).applying(Created(now)).andThen { _ =>
        requester ! Ack
      }
    case Event(VerifyJwt(_), _) =>
      stay replying InvalidJwt("Unknown scheduler")
  }

  when(Active) {
    case Event(VerifyJwt(token), _) =>
      val response = JwtJson.decode(token)
        .toOption.toRight("Failed to verify JWT without key")
        .right.flatMap { claim =>
          if (claim.isValid) Right(claim)
          else Left("Claim is not valid at this time")
        }
        .fold[VerifyJwtResponse](InvalidJwt(_), _ => ValidJwt)
      stay replying response
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
      currentData: Data): Data = (domainEvent, currentData) match {
    case (Created(_), _) =>
      SchedulerInfo
    case p =>
      throw new Exception(s"Unknown state/data combination: $p")
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

}