package domain

import akka.actor._
import akka.persistence.fsm._
import pdi.jwt._
import domain.InstanceAggregate.{Data, DomainEvent, State}
import scala.concurrent.duration._
import scala.reflect._
import com.softwaremill.tagging._
import services.ClusterSharder
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

object InstanceAggregate {

  type JwtValidator = String => Either[String, Unit]

  trait PublicJwk extends JwtValidator {
    def toJson: JsObject
  }

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
      clusterId: String,
      jwk: Option[PublicJwk] = None,
      uri: Option[String] = None,
      imageUrl: Option[String] = None,
      timestamps: EventTimestamps = EventTimestamps()) extends Data

  case class EventTimestamps(
      created: Option[Instant] = None,
      completed: Option[Instant] = None)

  sealed trait Command
  case object GetStatus extends Command
  case object GetJwk extends Command
  case class VerifyJwt(token: String) extends Command
  case object Save extends Command
  case object Discard extends Command
  case class RecordInstanceStart(clusterId: String) extends Command
  case class AssociateRsaPublicKey(publicExponent: BigInt, modulus: BigInt) extends Command
  case class AssociateUri(uri: String) extends Command
  case class AssociateImage(uri: String) extends Command
  case class ReceiveInstanceStateUpdate(state: String) extends Command
  case object GetImage extends Command

  sealed trait Response
  case object Ack extends Response
  sealed trait StatusResponse extends Response
  case object DoesNotExist extends StatusResponse
  case class CurrentStatus(
      state: String, uri: Option[String], timestamps: EventTimestamps) extends StatusResponse
  sealed trait GetJwkResponse extends Response
  case class InstanceJwk(jwk: JsObject) extends GetJwkResponse
  case object NoJwkExists extends GetJwkResponse
  sealed trait VerifyJwtResponse extends Response
  case class ValidJwt(instanceId: String) extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse
  sealed trait GetImageResponse extends Response
  case class InstanceImage(url: String) extends GetImageResponse
  case object NoImageExists extends GetImageResponse

  sealed trait DomainEvent extends BaseDomainEvent
  case class StartedInstance(
      clusterId: String, timestamp: Instant = Instant.now) extends DomainEvent
  case class DiscardedInstance(timestamp: Instant = Instant.now) extends DomainEvent
  case class PreservedInstance(timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedRsaPublicKey(
      publicExponent: BigInt, modulus: BigInt, timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedUri(
      uri: String, timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedImage(
      uri: String, timestamp: Instant = Instant.now) extends DomainEvent
}

class InstanceAggregate(
    clusterSharder: ActorRef @@ ClusterSharder.type)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import InstanceAggregate._
  import context.dispatcher
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  implicit val m: Materializer = ActorMaterializer()

  lazy val instanceId: String = self.path.name
  override lazy val persistenceId: String = "Instance-"+self.path.name

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(GetStatus, _) =>
      stay replying DoesNotExist
    case Event(GetJwk, _) =>
      stay replying NoJwkExists
    case Event(VerifyJwt(token), _) =>
      stay replying InvalidJwt(s"$instanceId has not been initialized → $token")
    case Event(RecordInstanceStart(clusterId), _) =>
      val requester = sender
      goto(Started).applying(StartedInstance(clusterId)).andThen { _ =>
        requester ! Ack
      }
  }

  when(Started) {
    case Event(GetStatus, InstanceData(clusterId, _, _, _, _)) =>
      // Start actor listening
      context.actorOf(
          Props(classOf[GetStatusRequestActor], sender),
          "get-status-request-"+Random.alphanumeric.take(10).mkString)
      // Forward request to cluster
      context.system.eventStream.publish(
          ClusterSharder.Envelope(clusterId, ClusterAggregate.GetInstanceStatus(instanceId)))
      stay
    case Event(InstanceStateUpdate(instanceId, timestamp, state, info), InstanceData(clusterId, _, uri, imageUrl, ts)) =>
      // Tell any listening actors
      context.actorSelection("get-status-request-*") ! CurrentStatus(state.toString, uri, ts)
      state match {
        case InstanceStateUpdate.InstanceState.DISCARDED => goto(Discarded).applying(DiscardedInstance())
        case InstanceStateUpdate.InstanceState.UPLOADED => goto(Preserved).applying(PreservedInstance())
        case InstanceStateUpdate.InstanceState.UPLOADING if imageUrl.isDefined =>
          // Scheduler doesn't know we're done, so remind it
          clusterSharder ! ClusterSharder.Envelope(clusterId, ClusterAggregate.ConfirmInstanceUpload(instanceId))
          stay
        case _ => stay
      }
    case Event(GetJwk, InstanceData(clusterId, Some(jwk), _, _, _)) =>
      stay replying InstanceJwk(jwk.toJson)
    case Event(GetJwk, InstanceData(clusterId, None, _, _, _)) =>
      stay replying NoJwkExists
    case Event(VerifyJwt(token), InstanceData(clusterId, Some(jwk), _, _, _)) =>
      stay replying {
        jwk(token).fold(InvalidJwt(_), _ => ValidJwt(instanceId))
      }
    case Event(VerifyJwt(token), InstanceData(clusterId, None, _, _, _)) =>
      stay replying InvalidJwt("No JWK is associated with this instance")
    case Event(AssociateRsaPublicKey(e, n), InstanceData(_, Some(RsaJwk(ce, cn)), _, _, _)) if e == ce && n == cn =>
      // Same as current - no need to update
      stay replying Ack
    case Event(AssociateRsaPublicKey(e, n), _: InstanceData) =>
      val requester = sender
      stay applying AssociatedRsaPublicKey(e, n) andThen { _ =>
        requester ! Ack
      }
    case Event(AssociateUri(newUri), InstanceData(_, _, Some(currentUri), _, _)) if currentUri == newUri =>
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
    case Event(Save, InstanceData(clusterId, _, _, _, _)) =>
      clusterSharder forward ClusterSharder.Envelope(clusterId, ClusterAggregate.SaveInstance(instanceId))
      stay
    case Event(Discard, InstanceData(clusterId, _, _, _, _)) =>
      clusterSharder forward ClusterSharder.Envelope(clusterId, ClusterAggregate.DiscardInstance(instanceId))
      stay
  }

  when(Preserved) {
    case Event(GetStatus, InstanceData(clusterId, _, _, _, ts)) =>
      stay replying CurrentStatus(InstanceStateUpdate.InstanceState.UPLOADED.toString, None, ts)
    case Event(_: InstanceStateUpdate, _) =>
      // Do nothing - no further updates are necessary or possible
      stay
    case Event(GetJwk, InstanceData(clusterId, Some(jwk), _, _, _)) =>
      stay replying InstanceJwk(jwk.toJson)
    case Event(GetJwk, InstanceData(clusterId, None, _, _, _)) =>
      stay replying NoJwkExists
    case Event(VerifyJwt(token), InstanceData(clusterId, Some(jwk), _, _, _)) =>
      stay replying {
        jwk(token).fold(InvalidJwt(_), _ => ValidJwt(instanceId))
      }
    case Event(VerifyJwt(token), InstanceData(clusterId, None, _, _, _)) =>
      stay replying InvalidJwt("No JWK is associated with this instance")
    case Event(_: AssociateRsaPublicKey, _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateUri(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateImage(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(Save, InstanceData(clusterId, _, _, _, _)) =>
      stay
    case Event(Discard, InstanceData(clusterId, _, _, _, _)) =>
      stay
    case Event(GetImage, InstanceData(_, _, _, Some(imageUrl), _)) =>
      stay replying InstanceImage(imageUrl)
    case Event(GetImage, InstanceData(_, _, _, None, _)) =>
      stay replying NoImageExists
  }

  when(Discarded) {
    case Event(GetStatus, InstanceData(clusterId, _, _, _, ts)) =>
      stay replying CurrentStatus(InstanceStateUpdate.InstanceState.DISCARDED.toString, None, ts)
    case Event(_: InstanceStateUpdate, _) =>
      // Do nothing - no further updates are necessary or possible
      stay
    case Event(GetJwk, _) =>
      stay replying NoJwkExists
    case Event(VerifyJwt(token), _) =>
      stay replying InvalidJwt("Instance has been discarded")
    case Event(_: AssociateRsaPublicKey, _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateUri(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(AssociateImage(uri), _) =>
      // Do nothing - no further updates are necessary or possible
      stay replying Ack
    case Event(Save, InstanceData(clusterId, _, _, _, _)) =>
      stay
    case Event(Discard, InstanceData(clusterId, _, _, _, _)) =>
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
      case StartedInstance(clusterId, ts) => InstanceData(clusterId, timestamps = EventTimestamps(Some(ts)) )
      case PreservedInstance(ts) => currentData match {
        case data: InstanceData => data.copy(timestamps = data.timestamps.copy(completed = Some(ts)))
        case _ => unhandled
      }
      case DiscardedInstance(ts) => currentData match {
        case data: InstanceData => data.copy(timestamps = data.timestamps.copy(completed = Some(ts)))
        case _ => unhandled
      }
      case AssociatedRsaPublicKey(e, n, _) => currentData match {
        case data: InstanceData => data.copy(jwk = Some(RsaJwk(e, n)))
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

  case class RsaJwk(publicExponent: BigInt, modulus: BigInt) extends PublicJwk {
    val key: PublicKey =
      KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus.bigInteger, publicExponent.bigInteger))

    override def apply(token: String) =
      JwtJson.decode(token, key)
        .toOption.toRight("Failed to verify JWT with key")
        .right.flatMap { claim =>
          if (claim.isValid) Right(())
          else Left("Claim is not valid at this time")
        }

    override def toJson =
      Json.obj(
          "kty" -> "RSA",
          "e" -> base64Url(publicExponent),
          "n" -> base64Url(modulus))

    protected def base64Url(bi: BigInt): String =
      JwtBase64.encodeString(bi.toByteArray)
  }

}

class GetStatusRequestActor(requester: ActorRef) extends Actor {
  override def receive = {
    case msg =>
      requester.tell(msg, context.parent)
      context.stop(self)
  }
}