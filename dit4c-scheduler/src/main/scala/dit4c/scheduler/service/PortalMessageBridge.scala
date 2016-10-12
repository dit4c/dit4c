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
import dit4c.scheduler.domain.Instance
import java.time.Instant
import scala.util.Random

object PortalMessageBridge {
  case object BridgeClosed
  class UnmarshallingActor extends Actor with ActorLogging {
    implicit val materializer = ActorMaterializer()

    val receive: Receive = LoggingReceive {
      case msg: BinaryMessage =>
        implicit val ec = context.dispatcher
        toByteString(msg).foreach { bs =>
          import dit4c.protobuf.scheduler.inbound.InboundMessage
          import InboundMessage.Payload
          InboundMessage.parseFrom(bs.toArray).payload match {
            case Payload.Empty => // Do nothing
            case Payload.StartInstance(value) => sendToParent(value)
            case Payload.DiscardInstance(value) => sendToParent(value)
            case Payload.SaveInstance(value) => sendToParent(value)
          }
        }
      case msg: TextMessage =>
        // Ignore text messages, but drain to avoid problems
        val addPrefix = "Text from portal: ".concat _
        msg.textStream.runForeach(addPrefix.andThen(log.info))
      case BridgeClosed =>
        log.debug("portal message bridge closed - sending to parent")
        context.parent ! BridgeClosed
      case akka.actor.Status.Failure(e) =>
        log.error(s"portal message bridge connection failed: $e")
        context.parent ! BridgeClosed
      case msg =>
        throw new Exception(s"Unknown message: $msg")
    }

    def sendToParent[M](msg: M) { context.parent ! msg }

    def toByteString(binaryMessage: BinaryMessage): Future[ByteString] =
        binaryMessage.dataStream.runReduce((m: ByteString, n: ByteString) => m ++ n)
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

    outboundSource = Source.actorRef(1, OverflowStrategy.fail)
    def outboundActorRefExtractor(nu: NotUsed, ref: ActorRef) = ref
    outbound = Http()(context.system).singleWebSocketRequest(
        WebSocketRequest.fromTargetUri(websocketUrl),
        Flow.fromSinkAndSourceMat(
            inboundSink, outboundSource)(outboundActorRefExtractor))._2
    context.watch(outbound)
  }

  val receive: Receive = {
    // Inbound
    case dit4c.protobuf.scheduler.inbound.StartInstance(instanceId, clusterId, imageUrl) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.StartInstance(instanceId, Instance.NamedImage(imageUrl), portalUri))
    case dit4c.protobuf.scheduler.inbound.DiscardInstance(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.service._
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.TerminateInstance(instanceId))
    // Outbound
    case Instance.StatusReport(state, data: Instance.StartData) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val pbState = state match {
        case Instance.WaitingForImage => pb.InstanceStateUpdate.InstanceState.CREATED
        case Instance.Starting => pb.InstanceStateUpdate.InstanceState.PRESTART
        case Instance.Running => pb.InstanceStateUpdate.InstanceState.STARTED
        case Instance.Stopping => pb.InstanceStateUpdate.InstanceState.STARTED
        case Instance.Finished => pb.InstanceStateUpdate.InstanceState.EXITED
      }
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate.apply(data.instanceId, Some(pbTimestamp(Instant.now)), pbState, "")
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
    case Instance.StatusReport(Instance.Errored, data: Instance.ErrorData) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate.apply(data.instanceId, Some(pbTimestamp(Instant.now)),
            pb.InstanceStateUpdate.InstanceState.ERRORED, data.errors.mkString("\n\n"))
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