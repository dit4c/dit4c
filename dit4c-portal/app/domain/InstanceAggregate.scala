package domain

import akka.actor._
import akka.persistence.fsm._
import pdi.jwt._
import domain.InstanceAggregate.{Data, DomainEvent, State}
import scala.concurrent.duration._
import scala.reflect._
import com.softwaremill.tagging._
import services.ClusterAggregateManager
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

object InstanceAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Started extends State

  sealed trait Data
  case object NoData extends Data
  case class InstanceData(
      clusterId: String,
      uri: Option[String] = None) extends Data

  sealed trait Command
  case object GetStatus extends Command
  case class VerifyJwt(token: String) extends Command
  case object Terminate extends Command
  case class RecordInstanceStart(clusterId: String) extends Command
  case class AssociateUri(uri: String) extends Command

  sealed trait Response
  case object Ack extends Response
  sealed trait StatusResponse extends Response
  case object DoesNotExist extends StatusResponse
  case class RemoteStatus(
      state: String, uri: Option[String]) extends StatusResponse
  sealed trait VerifyJwtResponse extends Response
  case class ValidJwt(instanceId: String) extends VerifyJwtResponse
  case class InvalidJwt(msg: String) extends VerifyJwtResponse

  sealed trait DomainEvent extends BaseDomainEvent
  case class StartedInstance(
      clusterId: String, timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedUri(
      uri: String, timestamp: Instant = Instant.now) extends DomainEvent
}

class InstanceAggregate(
    instanceId: String,
    clusterAggregateManager: ActorRef @@ ClusterAggregateManager)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import InstanceAggregate._
  import context.dispatcher
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  implicit val m: Materializer = ActorMaterializer()

  override lazy val persistenceId: String = self.path.name

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(GetStatus, _) =>
      stay replying DoesNotExist
    case Event(RecordInstanceStart(clusterId), _) =>
      val requester = sender
      goto(Started).applying(StartedInstance(clusterId)).andThen { _ =>
        requester ! Ack
      }
  }

  when(Started) {
    case Event(GetStatus, InstanceData(clusterId, maybeUri)) =>
      implicit val timeout = Timeout(1.minute)
      log.info(s"Fetching remote instance status ($instanceId)")
      val futureResponse: Future[StatusResponse] =
        getStatusFromCluster(clusterId).map {
          case msg @ RemoteStatusInfo(state, _, _) =>
            log.info(s"Received instance status ($instanceId): $msg")
            RemoteStatus(state, maybeUri)
        }
      futureResponse pipeTo sender
      stay
    case Event(VerifyJwt(token), InstanceData(clusterId, _)) =>
      implicit val timeout = Timeout(1.minute)
      log.debug(s"Fetching remote instance status ($instanceId)")
      val futureResponse: Future[VerifyJwtResponse] =
        getStatusFromCluster(clusterId).map {
          case msg @ RemoteStatusInfo(_, Some(key), _) =>
            log.debug(s"Received instance status ($instanceId): $msg")
            JwtJson.decode(token, key)
              .toOption.toRight("Failed to verify JWT with key")
              .right.flatMap { claim =>
                if (claim.isValid) Right(claim)
                else Left("Claim is not valid at this time")
              }
              .fold[VerifyJwtResponse](InvalidJwt(_), _ => ValidJwt(instanceId))
        }
      futureResponse pipeTo sender
      stay
    case Event(AssociateUri(newUri), InstanceData(_, Some(currentUri))) if currentUri == newUri =>
      // Same as current - no need to update
      sender ! Ack
      stay
    case Event(AssociateUri(uri), InstanceData(_, _)) =>
      val requester = sender
      stay applying AssociatedUri(uri) andThen { _ =>
        requester ! Ack
      }
    case Event(Terminate, InstanceData(clusterId, _)) =>
      implicit val timeout = Timeout(1.minute)
      (clusterAggregateManager ? ClusterAggregateManager.ClusterEnvelope(
        clusterId, ClusterAggregate.TerminateInstance(instanceId)))
        .pipeTo(sender)
      stay
  }

  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = (domainEvent, currentData) match {
    case (StartedInstance(clusterId, _), _) => InstanceData(clusterId)
    case (AssociatedUri(uri, _), data: InstanceData) =>
      data.copy(uri = Some(uri))
    case p =>
      throw new Exception(s"Unknown state/data combination: $p")
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  private case class RemoteStatusInfo(
      state: String,
      key: Option[PublicKey],
      errors: Seq[String])


  implicit val readsBigInteger: Reads[BigInteger] =
    Reads {
      case JsString(s) =>
        try {
          JsSuccess(BigInt(JwtBase64.decode(s)).bigInteger)
        } catch {
          case e: Throwable => JsError(e.getMessage)
        }
      case _ => JsError("Not a string")
    }

  private implicit val readsPublicKey: Reads[PublicKey] = {
    import play.api.libs.json.Reads.JsObjectReads
    import java.security.KeyFactory
    import java.security.spec.RSAPublicKeySpec
    val keyTypeSelector: Reads[String] = (__ \ 'jwk \ 'kty).read[String]
    def keyType(obj: JsObject): Option[String] =
      Json.fromJson(obj)(keyTypeSelector).asOpt
    JsObjectReads.flatMap {
      case obj if keyType(obj) == Some("RSA") =>
        (
          (__ \ 'jwk \ 'e).read[BigInteger] and
          (__ \ 'jwk \ 'n).read[BigInteger]
        ) { (publicExponent: BigInteger, modulus: BigInteger) =>
          val spec = new RSAPublicKeySpec(modulus, publicExponent)
          val factory = KeyFactory.getInstance("RSA")
          factory.generatePublic(spec)
        }
    }
  }

  private implicit val readsRemoteStatus: Reads[RemoteStatusInfo] = (
      (__ \ 'state).read[String] and
      (__ \ 'key).readNullable[PublicKey] and
      (__ \ 'errors).readNullable[Seq[String]].map(_.getOrElse(Seq.empty))
  )(RemoteStatusInfo.apply _)

  private def getStatusFromCluster(
      clusterId: String
      )(implicit timeout: akka.util.Timeout): Future[RemoteStatusInfo] =
    (clusterAggregateManager ? ClusterAggregateManager.ClusterEnvelope(
      clusterId, ClusterAggregate.GetInstanceStatus(instanceId))).flatMap {
        case ClusterAggregate.InstanceStatus(
            HttpResponse(StatusCodes.OK, headers, entity, _)) =>
          Unmarshal(entity).to[RemoteStatusInfo]
        case HttpResponse(StatusCodes.NotFound, _, _, _) =>
          throw new Exception("Not found on server")
    }

}