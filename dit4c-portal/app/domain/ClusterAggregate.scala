package domain

import akka.actor._
import com.softwaremill.tagging._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import scala.concurrent.Future
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import services.SchedulerSharder
import akka.persistence.fsm._
import ClusterAggregate._
import java.time.Instant
import scala.reflect._
import scala.util.Random
import utils.IdUtils
import domain.cluster.DomainEvent

object ClusterAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Active extends State

  sealed trait Data
  case object NoData extends Data
  case class ClusterInfo(schedulerId: String) extends Data

  sealed trait Command extends BaseResponse
  case class Create(schedulerId: String) extends Command
  case class StartInstance(instanceId: String, image: String) extends Command
  case class GetInstanceStatus(instanceId: String) extends Command
  case class SaveInstance(instanceId: String) extends Command
  case class DiscardInstance(instanceId: String) extends Command
  case class ConfirmInstanceUpload(instanceId: String) extends Command

  sealed trait Response extends BaseResponse
  case object Ack extends Response
  case class AllocatedInstanceId(clusterId: String, instanceId: String) extends Response

}

class ClusterAggregate(
    schedulerSharder: ActorRef @@ SchedulerSharder.type,
    imageServerConfig: ImageServerConfig)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
  import BaseDomainEvent._
  import domain.cluster._
  import ClusterAggregate._
  import play.api.libs.json._
  import akka.pattern.pipe

  lazy val clusterId = self.path.name
  override lazy val persistenceId: String = "Cluster-" + self.path.name

  implicit val m: Materializer = ActorMaterializer()
  import context.dispatcher

  startWith(Uninitialized, NoData)

  when(Uninitialized) {
    case Event(Create(schedulerId), _) =>
      val requester = sender
      goto(Active).applying(Created(schedulerId, now)).andThen { _ =>
        requester ! Ack
      }
  }

  when(Active) {
    case Event(StartInstance(instanceId, image), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).startInstance(instanceId, image)
      stay
    case Event(GetInstanceStatus(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).getInstanceStatus(instanceId)
      stay
    case Event(SaveInstance(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).saveInstance(instanceId)
      stay
    case Event(DiscardInstance(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).discardInstance(instanceId)
      stay
    case Event(ConfirmInstanceUpload(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).confirmUploadedInstance(instanceId)
      stay
  }

  override def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Data = (domainEvent, currentData) match {
    case (Created(schedulerId, _), _) =>
      ClusterInfo(schedulerId)
    case p =>
      throw new Exception(s"Unknown state/data combination: $p")
  }

  override def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  case class SchedulerMessage(schedulerId: String) {

    def startInstance(instanceId: String, image: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.StartInstance(
        StartInstance(instanceId, clusterId, image)
      ))
    }

    def saveInstance(instanceId: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.SaveInstance(
        SaveInstance(instanceId, clusterId, imageServerConfig.saveHelper, imageServerConfig.server)
      ))
    }

    def discardInstance(instanceId: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.DiscardInstance(
        DiscardInstance(instanceId, clusterId)
      ))
    }

    def confirmUploadedInstance(instanceId: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.ConfirmInstanceUpload(
        ConfirmInstanceUpload(instanceId, clusterId)
      ))
    }

    def getInstanceStatus(instanceId: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.RequestInstanceStateUpdate(
        RequestInstanceStateUpdate(instanceId, clusterId)
      ))
    }

    private def wrapForScheduler(msg: dit4c.protobuf.scheduler.inbound.InboundMessage) =
      SchedulerSharder.Envelope(schedulerId, SchedulerAggregate.SendSchedulerMessage(msg))

    private def randomMsgId = IdUtils.timePrefix + IdUtils.randomId(16)

  }

}