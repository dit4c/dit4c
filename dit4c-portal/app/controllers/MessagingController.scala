package controllers

import com.softwaremill.tagging._
import play.api.Environment
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import play.api.mvc.RequestHeader
import scala.concurrent.Future
import services.SchedulerSharder
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import domain.SchedulerAggregate
import scala.concurrent.ExecutionContext
import akka.actor.Actor
import akka.actor.ActorLogging
import play.api.http.websocket._
import akka.actor.ActorSystem
import akka.actor.Props
import scala.util.Random
import play.api.libs.streams.ActorFlow
import akka.stream.Materializer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import play.http.websocket.Message.Close
import akka.util.ByteString
import java.time.Instant
import akka.actor.Cancellable
import akka.remote.Ack

class MessagingController(
    environment: Environment,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)(
        implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext)
    extends Controller {

  implicit val timeout = Timeout(5.seconds)

  def schedulerSocket(schedulerId: String) = WebSocket { request: RequestHeader =>
    val wsSessionId = randomId
    val gateway = system.actorOf(
        Props(classOf[SchedulerGatewayActor], schedulerId, schedulerSharder),
        s"scheduler-gateway-$schedulerId-$wsSessionId")
    (gateway ? SchedulerAggregate.VerifyJwt(dummyJwt)).flatMap {
      case SchedulerAggregate.ValidJwt =>
        (gateway ? SchedulerGatewayActor.Connect).map(v => Right(v.asInstanceOf[Flow[Message,Message,_]]))
      case SchedulerAggregate.InvalidJwt(msg) =>
        gateway ! SchedulerGatewayActor.Done
        Future.successful(Left(Forbidden(msg)))
    }
  }

  private val dummyJwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.e30."

  private def randomId = Random.alphanumeric.take(8).mkString

}

object SchedulerGatewayActor {
  case object Connect
  case class Up(socketActor: ActorRef)
  case object Done
}

class SchedulerGatewayActor(schedulerId: String, schedulerSharder: ActorRef)
    extends Actor
    with ActorLogging {
  implicit lazy val materializer = ActorMaterializer()

  var wsFlow: Option[Flow[Message,Message,_]] = None
  var messagingActor: Option[ActorRef] = None

  override def receive = {
    case SchedulerGatewayActor.Connect if wsFlow.isEmpty =>
      wsFlow = Some(ActorFlow.actorRef[Message, Message] { out =>
        Props(classOf[SchedulerMessagingActor], self, out)
      })
      sender ! wsFlow.get
    case SchedulerGatewayActor.Up(ref) =>
      messagingActor = Some(ref)
      log.info("registering socket")
      schedulerSharder ! wrapForSharder(SchedulerAggregate.RegisterSocket(ref))
    case SchedulerGatewayActor.Done =>
      messagingActor.foreach { ref =>
        log.info("deregistering socket")
        schedulerSharder ! wrapForSharder(SchedulerAggregate.DeregisterSocket(ref))
      }
      log.info("terminating")
      context.stop(self)
    case msg: Any =>
      schedulerSharder.forward(wrapForSharder(msg))
  }

  def wrapForSharder(msg: Any) = SchedulerSharder.Envelope(schedulerId, msg)

}

class SchedulerMessagingActor(in: ActorRef, out: ActorRef)
    extends Actor
    with ActorLogging {

  var keepAlive: Option[Cancellable] = None

  override def preStart = {
    import context.dispatcher
    keepAlive = Some(
        context.system.scheduler.schedule(1.second, 20.seconds, self, Instant.now.toString))
    in ! SchedulerGatewayActor.Up(self)
  }

  override def postStop = {
    log.info("stopped")
    keepAlive.foreach(_.cancel)
    in ! SchedulerGatewayActor.Done
    super.postStop
  }

  override def receive = {
    // From client
    case msg: TextMessage =>
      log.info(s"Text from scheduler: $msg")
    case msg: BinaryMessage =>
      val parsedMsg = dit4c.protobuf.scheduler.outbound.OutboundMessage.parseFrom(msg.data.toArray)
      log.info(s"Msg from scheduler: $parsedMsg")
      in ! SchedulerAggregate.ReceiveSchedulerMessage(parsedMsg)
    // From portal
    case msg: String =>
      log.info(s"Sending text to client: $msg")
      out ! TextMessage(msg)
    case msg: dit4c.protobuf.scheduler.inbound.InboundMessage =>
      out ! BinaryMessage(ByteString(msg.toByteArray))
    case unknown =>
      log.error(s"Unhandled message: $unknown")
      context.stop(self)
  }

}