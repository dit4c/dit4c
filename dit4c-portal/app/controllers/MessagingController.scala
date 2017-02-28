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
import play.api.mvc.Action
import akka.http.scaladsl.model.StatusCodes.ServerError
import domain.SchedulerAggregate.UpdateKeys
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature

class MessagingController(
    environment: Environment,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)(
        implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext)
    extends Controller {

  implicit val timeout = Timeout(5.seconds)

  def schedulerRegistration = Action.async(parse.multipartFormData) { request =>
    request.body.file("keys") match {
      case None =>
        Future.successful(BadRequest("\"keys\" missing from form data"))
      case Some(keys) if (keys.ref.file.length > (10L*1024*1024)) =>
        Future.successful(InternalServerError(
            "Key blocks larger than 10MiB are not supported"))
      case Some(keys) =>
        import dit4c.common.KeyHelpers._
        import scala.sys.process._
        val keyContent = keys.ref.file.cat.!!
        parseArmoredPublicKeyRing(keyContent) match {
          case Left(msg) =>
            Future.successful(InternalServerError(msg))
          case Right(pkr) =>
            val id = pkr.getPublicKey.fingerprint.string
            val cmd = SchedulerSharder.Envelope(
                id, SchedulerAggregate.UpdateKeys(keyContent))
            (schedulerSharder ? cmd).map {
              case SchedulerAggregate.KeysUpdated =>
                Redirect(routes.MessagingController.schedulerSocket(id))
              case SchedulerAggregate.KeysRejected(msg) =>
                Forbidden(msg)
            }
        }
    }
  }

  def messageToScheduler(schedulerId: String) = Action.async(parse.multipartFormData) { request =>
    def envelope[A](msg: A) =
      SchedulerSharder.Envelope(schedulerId, msg)
    (schedulerSharder ? envelope(SchedulerAggregate.GetKeys))
      .map(_.asInstanceOf[SchedulerAggregate.GetKeysResponse])
      .flatMap {
        case currentKeys: SchedulerAggregate.CurrentKeys =>
          import dit4c.common.KeyHelpers._
          val schedulerKeys = parseArmoredPublicKeyRing(currentKeys.primaryKeyBlock).right.get
          request.body.file("msg") match {
            case None =>
              Future.successful(
                  BadRequest("\"msg\" missing from form data"))
            case Some(msgFile) if (msgFile.ref.file.length > (10L*1024*1024)) =>
              Future.successful(
                  InternalServerError("Messages larger than 10MiB are not supported"))
            case Some(msgFile) =>
              import dit4c.common.KeyHelpers._
              import scala.sys.process._
              val msg = msgFile.ref.file.cat.!!
              verifyData(ByteString(msg.getBytes), schedulerKeys.signingKeys) match {
                case Left(msg) => Future.successful(BadRequest(msg))
                case Right((_, sigs)) if sigs.isEmpty =>
                  Future.successful(
                      Forbidden("Signed message is not from source trusted by scheduler"))
                case Right(_) =>
                  val cmd = envelope(
                    SchedulerAggregate.SendSchedulerMessage(
                      dit4c.protobuf.scheduler.inbound.InboundMessage(
                        randomId,
                        dit4c.protobuf.scheduler.inbound.InboundMessage.Payload.SignedMessageForScheduler(
                          dit4c.protobuf.scheduler.inbound.SignedMessageForScheduler(
                            msg)))))
                  (schedulerSharder ? cmd).map {
                    case SchedulerAggregate.MessageSent =>
                      Ok("")
                    case SchedulerAggregate.UnableToSendMessage =>
                      InternalServerError("Unable to send message")
                  }
              }
          }
        case SchedulerAggregate.NoKeysAvailable =>
          Future.successful(
            Forbidden("No keys are currently associated with the scheduler"))
      }
  }

  def schedulerSocket(schedulerId: String) = WebSocket { request: RequestHeader =>
    val wsSessionId = randomId
    val gateway = system.actorOf(
        Props(classOf[SchedulerGatewayActor], schedulerId, schedulerSharder),
        s"scheduler-gateway-$schedulerId-$wsSessionId")
    val authHeaderRegex = "^Bearer (.*)$".r
    request.headers.get("Authorization").collectFirst {
      case authHeaderRegex(token) => token
    } match {
      case Some(token) =>
        (gateway ? SchedulerAggregate.VerifyJwt(token)).flatMap {
          case SchedulerAggregate.ValidJwt =>
            (gateway ? SchedulerGatewayActor.Connect).map { v =>
              Right(v.asInstanceOf[Flow[Message,Message,_]])
            }
          case SchedulerAggregate.InvalidJwt(msg) =>
            gateway ! SchedulerGatewayActor.Done
            Future.successful(Left(Forbidden(msg)))
        }
      case None =>
        Future.successful(Left(Forbidden("Missing auth token")))
    }
  }

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
  case object KeepAlive

  override def preStart = {
    import context.dispatcher
    keepAlive = Some(
        context.system.scheduler.schedule(1.second, 20.seconds, self, KeepAlive))
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
      log.debug(s"Msg from scheduler: $parsedMsg")
      in ! SchedulerAggregate.ReceiveSchedulerMessage(parsedMsg)
    // From portal
    case KeepAlive =>
      import dit4c.protobuf.scheduler.inbound._
      log.debug("Sending keep-alive")
      val keepAlive = InboundMessage("keep-alive", InboundMessage.Payload.Empty)
      out ! BinaryMessage(ByteString(keepAlive.toByteArray))
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