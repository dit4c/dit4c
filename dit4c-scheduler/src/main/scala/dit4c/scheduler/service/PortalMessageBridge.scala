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
    case dit4c.protobuf.scheduler.inbound.StartInstance(instanceId, clusterId, imageUrl) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.service._
      // TODO: Fix to include instanceId
      context.parent ! ClusterAggregateManager.ClusterCommand(clusterId,
          RktClusterManager.StartInstance(instanceId, Instance.NamedImage(imageUrl), portalUri))
    case PortalMessageBridge.BridgeClosed =>
      log.info(s"bridge closed → terminating outbound actor")
      outbound ! akka.actor.Status.Success(NotUsed)
    case Terminated(ref) if ref == outbound =>
      log.info(s"shutting down after outbound actor terminated")
      context.stop(self)
  }

  protected lazy val portalUri: String = {
    val scheme = Uri(websocketUrl).scheme match {
      case "ws" => "http"
      case "wss" => "https"
      case other => other
    }
    Uri(websocketUrl).copy(scheme = scheme, path = Uri.Path.Empty, rawQueryString = None, fragment = None).toString
  }

}