package dit4c.scheduler.service

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.scaladsl._
import akka.stream.OverflowStrategy
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.util.ByteString
import scala.concurrent.Future
import akka.http.scaladsl.model.ws.TextMessage
import akka.event.LoggingReceive
import akka.http.scaladsl.model.Uri
import dit4c.scheduler.domain.RktClusterManager
import dit4c.scheduler.domain.Instance
import java.time.Instant
import scala.util.Random
import dit4c.common.KryoSerializable

object PortalMessageBridge {
  case object BridgeClosed extends KryoSerializable
  class UnmarshallingActor extends Actor with ActorLogging {
    implicit val materializer = ActorMaterializer()

    val receive: Receive = LoggingReceive {
      case msg: BinaryMessage =>
        implicit val ec = context.dispatcher
        toByteString(msg).foreach { bs =>
          import dit4c.protobuf.scheduler.inbound.InboundMessage
          import InboundMessage.Payload
          val parsedMsg = InboundMessage.parseFrom(bs.toArray).payload match {
            case Payload.Empty => None // Do nothing
            case Payload.RequestInstanceStateUpdate(value) => Some(value)
            case Payload.StartInstance(value) => Some(value)
            case Payload.DiscardInstance(value) => Some(value)
            case Payload.SaveInstance(value) => Some(value)
            case Payload.ConfirmInstanceUpload(value) => Some(value)
          }
          parsedMsg.foreach { msg =>
            log.debug(s"portal sent message: $msg")
            sendToParent(msg)
          }
        }
      case msg: TextMessage =>
        // Ignore text messages, but drain to avoid problems
        val addPrefix = "Text from portal: ".concat _
        msg.textStream.runForeach(addPrefix.andThen(log.info))
      case BridgeClosed =>
        log.debug("portal message bridge closed - sending to parent")
        sendToParent(BridgeClosed)
      case akka.actor.Status.Failure(e) =>
        log.error(s"portal message bridge connection failed: $e")
        sendToParent(BridgeClosed)
      case msg =>
        throw new Exception(s"Unknown message: $msg")
    }

    def sendToParent[M](msg: M) { context.parent ! msg }

    def toByteString(binaryMessage: BinaryMessage): Future[ByteString] = binaryMessage match {
      case BinaryMessage.Strict(bs) => Future.successful(bs)
      case BinaryMessage.Streamed(dataStream) =>
        dataStream.runReduce((m: ByteString, n: ByteString) => m ++ n)
    }
  }
}

class PortalMessageBridge(websocketUrl: String) extends Actor with ActorLogging {

  implicit val materializer = ActorMaterializer()
  var outboundSource: Source[Message, ActorRef] = null
  var inboundSink: Sink[Message, NotUsed] = null
  var inbound: ActorRef = null
  var outbound: ActorRef = null

  // Everything is so closely linked that a child failure means we should shut everything down
  override val supervisorStrategy = AllForOneStrategy() {
    case _ => SupervisorStrategy.Escalate
  }

  override def preStart {
    inbound = context.watch(context.actorOf(Props[PortalMessageBridge.UnmarshallingActor], "unmarshaller"))
    inboundSink = Sink.actorRef(inbound, PortalMessageBridge.BridgeClosed)

    outboundSource = Source.actorRef(16, OverflowStrategy.fail)
    def outboundActorRefExtractor(nu: NotUsed, ref: ActorRef) = ref
    outbound = Http()(context.system).singleWebSocketRequest(
        WebSocketRequest.fromTargetUri(websocketUrl),
        Flow.fromSinkAndSourceMat(
            inboundSink, outboundSource)(outboundActorRefExtractor))._2
    context.watch(outbound)
  }

  val receive: Receive = LoggingReceive {
    // Inbound
    case dit4c.protobuf.scheduler.inbound.RequestInstanceStateUpdate(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.GetInstanceStatus(instanceId))
    case dit4c.protobuf.scheduler.inbound.StartInstance(instanceId, clusterId, imageUrl) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.StartInstance(instanceId, imageUrl, portalUri))
    case dit4c.protobuf.scheduler.inbound.SaveInstance(instanceId, clusterId, saveHelperImageUrl, imageServer) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId,
              Instance.Save(saveHelperImageUrl, imageServer)))
    case dit4c.protobuf.scheduler.inbound.DiscardInstance(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId, Instance.Discard))
    case dit4c.protobuf.scheduler.inbound.ConfirmInstanceUpload(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId, Instance.ConfirmUpload))
    // Outbound
    case Instance.StatusReport(Instance.Errored, data: Instance.ErrorData) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(data.instanceId, Some(pbTimestamp(Instant.now)),
            pb.InstanceStateUpdate.InstanceState.ERRORED, data.errors.mkString("\n\n"))
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
    case Instance.StatusReport(state, data: Instance.SomeData) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val pbState = state match {
        case Instance.JustCreated => pb.InstanceStateUpdate.InstanceState.CREATED
        case Instance.WaitingForImage => pb.InstanceStateUpdate.InstanceState.CREATED
        case Instance.Starting => pb.InstanceStateUpdate.InstanceState.STARTING
        case Instance.Running => pb.InstanceStateUpdate.InstanceState.STARTED
        case Instance.Stopping => pb.InstanceStateUpdate.InstanceState.STOPPING
        case Instance.Exited => pb.InstanceStateUpdate.InstanceState.EXITED
        case Instance.Saved => pb.InstanceStateUpdate.InstanceState.SAVED
        case Instance.Saving => pb.InstanceStateUpdate.InstanceState.SAVING
        case Instance.Uploading => pb.InstanceStateUpdate.InstanceState.UPLOADING
        case Instance.Uploaded => pb.InstanceStateUpdate.InstanceState.UPLOADED
        case Instance.Discarding => pb.InstanceStateUpdate.InstanceState.DISCARDING
        case Instance.Discarded => pb.InstanceStateUpdate.InstanceState.DISCARDED
        case Instance.Errored => pb.InstanceStateUpdate.InstanceState.ERRORED
      }
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(data.instanceId, Some(pbTimestamp(Instant.now)), pbState, "")
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
      data match {
        case data: Instance.StartData =>
          import dit4c.common.KeyHelpers._
          data.signingKey.foreach { key =>
              val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.AllocatedInstanceKey(
                pb.AllocatedInstanceKey(data.instanceId, key.armoredPgpPublicKeyBlock)))
              outbound ! toBinaryMessage(msg.toByteArray)
          }
        case _ => // No need to do anything
      }
    case RktClusterManager.UnknownInstance(instanceId) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(instanceId, Some(pbTimestamp(Instant.now)),
            pb.InstanceStateUpdate.InstanceState.UNKNOWN, "")
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
    case PortalMessageBridge.BridgeClosed =>
      log.info(s"bridge closed → terminating outbound actor")
      outbound ! akka.actor.Status.Success(NotUsed)
    case Terminated(ref) if ref == outbound =>
      log.info(s"shutting down after outbound actor terminated")
      context.stop(self)
  }

  /**
   * 128-bit identifier as hexadecimal
   *
   * Intended to be long enough that it's globally unlikely to have a collision,
   * but based on time so it can also be sorted.
   */
  protected def newMsgId = {
    val now = Instant.now
    f"${now.getEpochSecond}%016x".takeRight(10) + // 40-bit epoch seconds
    f"${now.getNano / 100}%06x" + // 24-bit 100 nanosecond slices
    f"${Random.nextLong}%016x" // 64-bits of random
  }

  protected def toBinaryMessage(bs: Array[Byte]): BinaryMessage = BinaryMessage(ByteString(bs))

  protected def pbTimestamp(t: Instant): com.google.protobuf.timestamp.Timestamp =
    com.google.protobuf.timestamp.Timestamp(t.getEpochSecond, t.getNano)

  protected lazy val portalUri: String = {
    val scheme = Uri(websocketUrl).scheme match {
      case "ws" => "http"
      case "wss" => "https"
      case other => other
    }
    Uri(websocketUrl).copy(scheme = scheme, path = Uri.Path.Empty, rawQueryString = None, fragment = None).toString
  }

}