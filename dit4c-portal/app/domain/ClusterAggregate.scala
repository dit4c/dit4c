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

object ClusterAggregate {

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Uninitialized extends State
  case object Active extends State

  sealed trait Data
  case object NoData extends Data
  case class ClusterInfo(schedulerId: String) extends Data

  sealed trait Command
  case class Create(schedulerId: String) extends Command
  case class StartInstance(image: String) extends Command
  case class GetInstanceStatus(instanceId: String) extends Command
  case class SaveInstance(instanceId: String) extends Command
  case class DiscardInstance(instanceId: String) extends Command

  sealed trait Response
  case object Ack extends Response
  case class AllocatedInstanceId(clusterId: String, instanceId: String) extends Response

  sealed trait DomainEvent extends BaseDomainEvent
  case class Created(schedulerId: String, timestamp: Instant = Instant.now) extends DomainEvent

}

class ClusterAggregate(schedulerSharder: ActorRef @@ SchedulerSharder.type)
    extends PersistentFSM[State, Data, DomainEvent]
    with LoggingPersistentFSM[State, Data, DomainEvent]
    with ActorLogging {
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
      goto(Active).applying(Created(schedulerId)).andThen { _ =>
        requester ! Ack
      }
  }

  when(Active) {
    case Event(StartInstance(image), ClusterInfo(schedulerId)) =>
      var instanceId = timePrefix+randomId(16)
      schedulerSharder forward SchedulerMessage(schedulerId).startInstance(instanceId, image)
      stay replying AllocatedInstanceId(clusterId, instanceId)
    case Event(GetInstanceStatus(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).getInstanceStatus(instanceId)
      stay
    case Event(SaveInstance(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).saveInstance(instanceId)
      stay
    case Event(DiscardInstance(instanceId), ClusterInfo(schedulerId)) =>
      schedulerSharder forward SchedulerMessage(schedulerId).discardInstance(instanceId)
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
        SaveInstance(instanceId, clusterId, "", "") // TODO: complete
      ))
    }

    def discardInstance(instanceId: String): SchedulerSharder.Envelope = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.DiscardInstance(
        DiscardInstance(instanceId, clusterId)
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

    private def randomMsgId = timePrefix + randomId(16)

  }

  /**
   * Prefix based on time for easy sorting
   */
  protected def timePrefix = {
    val now = Instant.now
    f"${now.getEpochSecond}%016x".takeRight(10) + // 40-bit epoch seconds
    f"${now.getNano / 100}%06x"// 24-bit 100 nanosecond slices
  }

  private lazy val randomId: (Int) => String = {
    val base32 = (Range.inclusive('a','z').map(_.toChar) ++ Range.inclusive(2,7).map(_.toString.charAt(0)))
    (length: Int) =>
      Stream.continually(Random.nextInt(32)).map(base32).take(length).mkString
  }


}