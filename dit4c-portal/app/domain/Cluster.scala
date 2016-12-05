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
import Cluster._
import java.time.Instant
import scala.reflect._
import scala.util.Random
import utils.IdUtils

object Cluster {

  sealed trait Command extends BaseResponse
  case class Create(schedulerId: String) extends Command
  case class StartInstance(instanceId: String, image: String) extends Command
  case class GetInstanceStatus(instanceId: String) extends Command
  case class SaveInstance(instanceId: String) extends Command
  case class DiscardInstance(instanceId: String) extends Command
  case class ConfirmInstanceUpload(instanceId: String) extends Command

  sealed trait Response extends BaseResponse
  case object Ack extends Response

}

class Cluster(
    imageServerConfig: ImageServerConfig)
    extends Actor
    with ActorLogging {
  import BaseDomainEvent._
  import Cluster._
  import play.api.libs.json._

  lazy val clusterId = self.path.name

  implicit val m: Materializer = ActorMaterializer()
  import context.dispatcher

  val receive: Receive = {
    case StartInstance(instanceId, image) =>
      context.parent forward SchedulerMessage.startInstance(instanceId, image)
    case GetInstanceStatus(instanceId) =>
      context.parent forward SchedulerMessage.getInstanceStatus(instanceId)
    case SaveInstance(instanceId) =>
      context.parent forward SchedulerMessage.saveInstance(instanceId)
    case DiscardInstance(instanceId) =>
      context.parent forward SchedulerMessage.discardInstance(instanceId)
    case ConfirmInstanceUpload(instanceId) =>
      context.parent forward SchedulerMessage.confirmUploadedInstance(instanceId)
  }

  case object SchedulerMessage {

    def startInstance(instanceId: String, image: String): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.StartInstance(
        StartInstance(instanceId, clusterId, image)
      ))
    }

    def saveInstance(instanceId: String): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.SaveInstance(
        SaveInstance(instanceId, clusterId, imageServerConfig.saveHelper, imageServerConfig.server)
      ))
    }

    def discardInstance(instanceId: String): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.DiscardInstance(
        DiscardInstance(instanceId, clusterId)
      ))
    }

    def confirmUploadedInstance(instanceId: String): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.ConfirmInstanceUpload(
        ConfirmInstanceUpload(instanceId, clusterId)
      ))
    }

    def getInstanceStatus(instanceId: String): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      InboundMessage(randomMsgId, InboundMessage.Payload.RequestInstanceStateUpdate(
        RequestInstanceStateUpdate(instanceId, clusterId)
      ))
    }

    private def wrapForScheduler(msg: dit4c.protobuf.scheduler.inbound.InboundMessage) =
      SchedulerAggregate.SendSchedulerMessage(msg)

    private def randomMsgId = IdUtils.timePrefix + IdUtils.randomId(16)

  }

}