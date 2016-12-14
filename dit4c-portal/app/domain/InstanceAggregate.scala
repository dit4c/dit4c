package domain

import akka.actor._
import akka.persistence.fsm._
import pdi.jwt._
import domain.InstanceAggregate.{Data, State}
import domain.instance.DomainEvent
import scala.concurrent.duration._
import scala.reflect._
import com.softwaremill.tagging._
import services.SchedulerSharder
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import akka.http.scaladsl.unmarshalling.Unmarshal
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import akka.stream.Materializer
import akka.stream.ActorMaterializer
import scala.concurrent.Future
import java.security.PublicKey
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import play.api.libs.json.JsObject
import java.security.spec.RSAPublicKeySpec
import java.security.KeyFactory
import scala.util.Random
import dit4c.protobuf.scheduler.outbound.InstanceStateUpdate
import org.bouncycastle.openpgp.PGPPublicKeyRing
import services.KeyRingSharder
import dit4c.common.KeyHelpers.PGPFingerprint
import scala.util._

object InstanceAggregate {

  type JwtValidator = String => Either[String, Unit]

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Started extends State
  case object Preserved extends State
  case object Discarded extends State

  sealed trait Data
  case object NoData extends Data
  case class InstanceData(
      schedulerId: String,
      clusterId: String,
      keyFingerprint: Option[PGPFingerprint] = None,
      uri: Option[String] = None,
      imageUrl: Option[String] = None,
      timestamps: EventTimestamps = EventTimestamps()) extends Data

  case class EventTimestamps(
      created: Option[Instant] = None,
      completed: Option[Instant] = None)

  sealed trait Command extends BaseCommand
  case object GetStatus extends Command
  case class VerifyJwt(token: String) extends Command
  case object Save extends Command
  case object Discard extends Command
  case class Start(
      schedulerId: String,
      clusterId: String,
      accessPassIds: List[String],
      image: String) extends Command
  case class AssociatePGPPublicKey(keyBlock: String) extends Command
  protected case class AssociatePGPPublicKeyFingerprint(
      keyFingerprint: PGPFingerprint) extends Command
  case class AssociateUri(uri: String) extends Command
  case class AssociateImage(uri: String) extends Command
  case class ReceiveInstanceStateUpdate(state: String) extends Command
  case object GetImage extends Command

  sealed trait Response extends BaseResponse
  case class Started(instanceId: String) extends Response
  case object Ack extends Response
  sealed trait StatusResponse extends Response
  case object DoesNotExist extends StatusResponse
  case class CurrentStatus(
      state: String, uri: Option[String], timestamps: EventTimestamps) extends StatusResponse
  sealed trait VerifyJwtResponse extends Response
  case class ValidJwt(instanceId: String) extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse
  sealed trait GetImageResponse extends Response
  case class InstanceImage(url: String) extends GetImageResponse
  case object NoImageExists extends GetImageResponse

}

class InstanceAggregate(
    keyringSharder: ActorRef @@ KeyRingSharder.type,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import BaseDomainEvent._
  import domain.instance._
  import InstanceAggregate._
  import context.dispatcher
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  import dit4c.common.KeyHelpers._
  implicit val m: Materializer = ActorMaterializer()

  lazy val instanceId: String = self.path.name
  override lazy val persistenceId: String = "Instance-"+self.path.name

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(GetStatus, _) =>
      stay replying DoesNotExist
    case Event(VerifyJwt(token), _) =>
      stay replying InvalidJwt(s"$instanceId has not been initialized → $token")
    case Event(Start(schedulerId, clusterId, image, accessPassIds), _) =>
      val requester = sender
      goto(Started).applying(StartedInstance(schedulerId, clusterId, now)).andThen { _ =>
        implicit val timeout = Timeout(1.minute)
        (schedulerSharder ? SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.ClusterEnvelope(
                clusterId,
                Cluster.StartInstance(instanceId, accessPassIds, image)))).map {
            case SchedulerAggregate.Ack =>
              Started(instanceId)
          }.pipeTo(requester)
      }
  }

  when(Started) {
    case Event(GetStatus, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      // Start actor listening
      context.actorOf(
          Props(classOf[GetStatusRequestActor], sender),
          "get-status-request-"+Random.alphanumeric.take(10).mkString)
      // Forward request to cluster
      context.system.eventStream.publish(
          SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.ClusterEnvelope(
                clusterId,
                Cluster.GetInstanceStatus(instanceId))))
      stay
    case Event(InstanceStateUpdate(instanceId, timestamp, state, info), InstanceData(schedulerId, clusterId, _, uri, imageUrl, ts)) =>
      // Tell any listening actors
      context.actorSelection("get-status-request-*") ! CurrentStatus(state.toString, uri, ts)
      state match {
        case InstanceStateUpdate.InstanceState.DISCARDED => goto(Discarded).applying(DiscardedInstance())
        case InstanceStateUpdate.InstanceState.UPLOADED => goto(Preserved).applying(PreservedInstance())
        case InstanceStateUpdate.InstanceState.UPLOADING if imageUrl.isDefined =>
          // Scheduler doesn't know we're done, so remind it
          schedulerSharder ! SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.ClusterEnvelope(
                clusterId,
                Cluster.ConfirmInstanceUpload(instanceId)))
          stay
        case _ => stay
      }
    case Event(VerifyJwt(token), InstanceData(schedulerId, clusterId, Some(keyFingerprint), _, _, _)) =>
      validateJwt(keyFingerprint)(token).pipeTo(sender)
      stay
    case Event(VerifyJwt(token), InstanceData(schedulerId, clusterId, None, _, _, _)) =>
      stay replying InvalidJwt("No JWK is associated with this instance")
    case Event(AssociatePGPPublicKey(keyBlock), InstanceData(_, _, possibleKeyFingerprint, _, _, _)) =>
      import dit4c.common.KeyHelpers._
      // This is a one-way command, so no response necessary
      KeyRingSharder.Envelope.forKeySubmission(keyBlock) match {
        case Left(msg) =>
          log.error(msg)
          // Ignore key
        case Right(envelope) if possibleKeyFingerprint.exists(_ != envelope.fingerprint) =>
          log.error(
              s"Instance $instanceId to re-associate key with different fingerprint: "+
              s"${envelope.fingerprint} != ${possibleKeyFingerprint.get}")
          // Ignore key
        case Right(envelope) =>
          import akka.pattern.{ask, pipe}
          import context.dispatcher
          implicit val timeout = Timeout(1.minute)
          import KeyRingAggregate._
          (keyringSharder ? envelope).foreach {
            case KeySubmissionAccepted(_) =>
              self ! AssociatePGPPublicKeyFingerprint(envelope.fingerprint)
            case KeySubmissionRejected(r) =>
              log.error(r)
          }
      }
      stay
    case Event(AssociatePGPPublicKeyFingerprint(fingerprint), InstanceData(_, _, possibleKeyFingerprint, _, _, _)) =>
      possibleKeyFingerprint match {
        case Some(f) if f != fingerprint =>
          log.error(
              s"Instance $instanceId to re-associate key with different fingerprint: "+
              s"${f} != ${fingerprint}")
          stay
        case _ =>
          stay applying AssociatedPGPPublicKey(fingerprint.string)
      }
    case Event(AssociateUri(newUri), InstanceData(_, _, _, Some(currentUri), _, _)) if currentUri == newUri =>
      // Same as current - no need to update
      stay replying Ack
    case Event(AssociateUri(uri), _: InstanceData) =>
      val requester = sender
      stay applying AssociatedUri(uri) andThen { _ =>
        requester ! Ack
      }
    case Event(AssociateImage(uri), _: InstanceData) =>
      val requester = sender
      stay applying AssociatedImage(uri) andThen { _ =>
        // TODO: Notify scheduler
        requester ! Ack
      }
    case Event(Save, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      schedulerSharder forward SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.ClusterEnvelope(
                clusterId,
                Cluster.SaveInstance(instanceId)))
      stay
    case Event(Discard, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      schedulerSharder forward SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.ClusterEnvelope(
                clusterId,
                Cluster.DiscardInstance(instanceId)))
      stay
  }

  when(Preserved) {
    case Event(GetStatus, InstanceData(schedulerId, clusterId, _, _, _, ts)) =>
      stay replying CurrentStatus(InstanceStateUpdate.InstanceState.UPLOADED.toString, None, ts)
    case Event(_: InstanceStateUpdate, _) =>
      // Do nothing - no further updates are necessary or possible
      stay
    case Event(VerifyJwt(token), InstanceData(schedulerId, clusterId, Some(keyFingerprint), _, _, _)) =>
      validateJwt(keyFingerprint)(token).pipeTo(sender)
      stay
    case Event(VerifyJwt(token), InstanceData(schedulerId, clusterId, None, _, _, _)) =>
      stay replying InvalidJwt("No JWK is associated with this instance")
    case Event(_: AssociatePGPPublicKey, _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateUri(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateImage(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(Save, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      stay
    case Event(Discard, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      stay
    case Event(GetImage, InstanceData(_, _, _, _, Some(imageUrl), _)) =>
      stay replying InstanceImage(imageUrl)
    case Event(GetImage, InstanceData(_, _, _, _, None, _)) =>
      stay replying NoImageExists
  }

  when(Discarded) {
    case Event(GetStatus, InstanceData(schedulerId, clusterId, _, _, _, ts)) =>
      stay replying CurrentStatus(InstanceStateUpdate.InstanceState.DISCARDED.toString, None, ts)
    case Event(_: InstanceStateUpdate, _) =>
      // Do nothing - no further updates are necessary or possible
      stay
    case Event(VerifyJwt(token), _) =>
      stay replying InvalidJwt("Instance has been discarded")
    case Event(_: AssociatePGPPublicKey, _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateUri(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateImage(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(Save, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      stay
    case Event(Discard, InstanceData(schedulerId, clusterId, _, _, _, _)) =>
      stay
  }

  whenUnhandled {
    case Event(GetImage, _) =>
      stay replying NoImageExists
  }

  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = {
    def unhandled: Data =
      try {
        currentData
      } finally {
        throw new Exception(s"Unknown state/data combination: ($domainEvent, $currentData)")
      }
    domainEvent match {
      case StartedInstance(schedulerId, clusterId, ts) =>
        InstanceData(schedulerId, clusterId, timestamps = EventTimestamps(ts) )
      case PreservedInstance(ts) => currentData match {
        case data: InstanceData =>
          data.copy(timestamps = data.timestamps.copy(completed = ts))
        case _ => unhandled
      }
      case DiscardedInstance(ts) => currentData match {
        case data: InstanceData =>
          data.copy(timestamps = data.timestamps.copy(completed = ts))
        case _ => unhandled
      }
      case AssociatedPGPPublicKey(fingerprintStr, _) => currentData match {
        case data: InstanceData =>
          data.copy(keyFingerprint = Some(PGPFingerprint(fingerprintStr)))
        case _ => unhandled
      }
      case AssociatedUri(uri, _) => currentData match {
        case data: InstanceData => data.copy(uri = Some(uri))
        case _ => unhandled
      }
      case AssociatedImage(uri, _) => currentData match {
        case data: InstanceData => data.copy(imageUrl = Some(uri))
        case _ => unhandled
      }
    }
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  private def validateJwt(
      fingerprint: PGPFingerprint)(token: String): Future[VerifyJwtResponse] = {
    import dit4c.common.KeyHelpers._
    implicit val timeout = Timeout(1.minute)
    val msg = KeyRingSharder.Envelope(
        fingerprint,
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
                      case Success(_) => ValidJwt(instanceId)
                      case Failure(e) => InvalidJwt(s"$fingerprint: ${e.getMessage}")
                    }
                  }
                  .reduce[VerifyJwtResponse] {
                    case (InvalidJwt(a), InvalidJwt(b)) =>
                      InvalidJwt(s"$a\n$b")
                    case _ => ValidJwt(instanceId)
                  }
            }
      }
  }

}

class GetStatusRequestActor(requester: ActorRef) extends Actor {
  override def receive = {
    case msg =>
      requester.tell(msg, context.parent)
      context.stop(self)
  }
}