package domain

import akka.actor._
import akka.persistence.fsm._
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

object InstanceAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Started extends State

  sealed trait Data
  case object NoData extends Data
  case class InstanceData(clusterId: String) extends Data

  sealed trait Command
  case object GetStatus extends Command
  case object Terminate extends Command
  case class RecordInstanceStart(clusterId: String) extends Command

  sealed trait Response
  case object Ack extends Response
  sealed trait StatusResponse extends Response
  case object DoesNotExist extends StatusResponse
  case class RemoteStatus(state: String) extends StatusResponse

  sealed trait DomainEvent
  case class StartedInstance(clusterId: String) extends DomainEvent

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
    case Event(GetStatus, InstanceData(clusterId)) =>
      implicit val timeout = Timeout(1.minute)
      log.info(s"Fetching remote instance status ($instanceId)")
      val futureResponse: Future[StatusResponse] =
        (clusterAggregateManager ? ClusterAggregateManager.ClusterEnvelope(
          clusterId, ClusterAggregate.GetInstanceStatus(instanceId))).flatMap {
            case ClusterAggregate.InstanceStatus(HttpResponse(StatusCodes.OK, headers, entity, _)) =>
              Unmarshal(entity).to[RemoteStatusInfo].map {
                case msg @ RemoteStatusInfo(state, _) =>
                  log.info(s"Received instance status ($instanceId): $msg")
                  RemoteStatus(state)
              }
        }
      futureResponse pipeTo sender
      stay
    case Event(Terminate, InstanceData(clusterId)) =>
      implicit val timeout = Timeout(1.minute)
      (clusterAggregateManager ? ClusterAggregateManager.ClusterEnvelope(
        clusterId, ClusterAggregate.TerminateInstance(instanceId)))
        .pipeTo(sender)
      stay
  }


  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = (domainEvent, currentData) match {
    case (StartedInstance(clusterId), _) => InstanceData(clusterId)
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  private case class RemoteStatusInfo(
      state: String,
      errors: Seq[String])

  private implicit val readsRemoteStatus: Reads[RemoteStatusInfo] = (
      (__ \ 'state).read[String] and
      (__ \ 'errors).readNullable[Seq[String]].map(_.getOrElse(Seq.empty))
  )(RemoteStatusInfo.apply _)

}