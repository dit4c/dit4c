package domain

import scala.concurrent.duration._

import akka.actor._
import akka.stream._
import akka.util.Timeout
import utils.IdUtils
import akka.util.ByteString

object Cluster {

  sealed trait Command extends BaseResponse
  case class StartInstance(instanceId: String, image: String, accessTokenIds: List[String]) extends Command
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
  import Cluster._

  lazy val clusterId = self.path.name

  implicit val m: Materializer = ActorMaterializer()

  val receive: Receive = {
    case StartInstance(instanceId, image, accessTokenIds) =>
      import akka.pattern.{ask, pipe}
      import context.dispatcher
      implicit val timeout = Timeout(10.seconds)
      accessTokenIds
        .map { accessTokenId =>
          (context.parent ? AccessPassManager.Envelope(accessTokenId, AccessPass.GetSignedPass))
            .map {
              case AccessPass.SignedPass(signedData) => List(signedData)
              case _ => Nil
            }
        }
        // Collapse to single future
        .reduce { (aF, bF) =>
          for (a <- aF; b <- bF) yield a ++ b
        }
        .map(SchedulerMessage.startInstance(instanceId, image, _))
        .pipeTo(context.parent)(sender)
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

    def startInstance(instanceId: String, image: String, signedClusterAccessPasses: List[ByteString]): SchedulerAggregate.SendSchedulerMessage = wrapForScheduler {
      import dit4c.protobuf.scheduler.inbound._
      import com.google.protobuf.ByteString
      InboundMessage(randomMsgId, InboundMessage.Payload.StartInstance(
        StartInstance(instanceId, clusterId, image,
            signedClusterAccessPasses.map(bs => ByteString.copyFrom(bs.toArray)))
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