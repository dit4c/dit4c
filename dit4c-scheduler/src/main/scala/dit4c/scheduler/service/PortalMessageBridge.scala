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
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.Multipart
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.RequestEntity
import akka.http.scaladsl.marshalling.Marshal
import akka.util.Timeout
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpResponse
import pdi.jwt.JwtClaim
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.model.headers.`Set-Cookie`
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.HttpHeader
import java.util.Base64
import dit4c.scheduler.domain.Cluster
import java.net.URLEncoder

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
            case Payload.SignedMessageForScheduler(value) => Some(value)
          }
          parsedMsg match {
            case None =>
              log.debug(s"portal sent empty keep-alive message")
            case Some(msg) =>
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

class PortalMessageBridge(keyManager: ActorRef, registrationUrl: String)
    extends Actor with ActorLogging with Stash {
  import dit4c.scheduler.service

  implicit val materializer = ActorMaterializer()
  var outboundSource: Source[Message, ActorRef] = null
  var inboundSink: Sink[Message, NotUsed] = null
  var inbound: ActorRef = null

  // Everything is so closely linked that a child failure means we should shut everything down
  override val supervisorStrategy = AllForOneStrategy() {
    case _ => SupervisorStrategy.Escalate
  }

  override def preStart {
    import context.dispatcher
    import akka.pattern.ask
    implicit val timeout = Timeout(5.seconds)
    inbound = context.watch(context.actorOf(Props[PortalMessageBridge.UnmarshallingActor], "unmarshaller"))
    inboundSink = Sink.actorRef(inbound, PortalMessageBridge.BridgeClosed)

    outboundSource = Source.actorRef(128, OverflowStrategy.dropHead)
    def outboundActorRefExtractor(nu: NotUsed, ref: ActorRef) = ref
    val setup = for {
      armoredPgpPublicKeyRing <- (keyManager ? KeyManager.GetPublicKeyInfo).collect {
        case KeyManager.PublicKeyInfo(fingerprint, keyBlock) =>
          keyBlock
      }
      payload <- {
        val formData = Multipart.FormData(Multipart.FormData.BodyPart.Strict(
            "keys",
            HttpEntity(
                MediaType.applicationWithFixedCharset("pgp-keys", HttpCharsets.`UTF-8`).toContentType,
                armoredPgpPublicKeyRing),
            Map("filename" -> "keys.asc")))
        Marshal(formData).to[RequestEntity]
      }
      (redirectUri, cookies) <- Http()(context.system)
        .singleRequest(
          HttpRequest(
              method=HttpMethods.POST,
              registrationUrl,
              entity=payload)
        )
        .collect {
          case r: HttpResponse if r.status.isRedirection => r
        }
        .map { r =>
          (
            Uri(r.getHeader("Location").get.value)
              .resolvedAgainst(registrationUrl),
            r.headers.toList.collect {
              case `Set-Cookie`(cookie) => cookie
            }
          )
        }
      authClaim = JwtClaim(expiration=Some(Instant.now.getEpochSecond+120))
      authToken <- (keyManager ? KeyManager.SignJwtClaim(authClaim)).collect {
        case KeyManager.SignedJwtTokens(token :: others) => token
      }
      websocketUri = redirectUri.copy(scheme = redirectUri.scheme match {
        case "http" => "ws"
        case "https" => "wss"
      })
    } yield {
      val outbound = Http()(context.system).singleWebSocketRequest(
          WebSocketRequest.fromTargetUri(websocketUri)
            .copy(extraHeaders=
              Authorization(OAuth2BearerToken(authToken)) ::
              cookies.map(c => Cookie(c.pair))
            ),
          Flow.fromSinkAndSourceMat(
              inboundSink, outboundSource)(outboundActorRefExtractor))._2
      self ! outbound
      context.parent ! ClusterManager.GetClusters
    }
    setup.recover({
      case e =>
        log.error(e, "Setup failed")
        context.stop(self)
    })
  }

  val receive: Receive = {
    case outbound: ActorRef if sender == self =>
      unstashAll()
      context.become(running(outbound))
      context.watch(outbound)
    case msg => stash()
  }

  def running(outbound: ActorRef): Receive = LoggingReceive {
    // Inbound
    case dit4c.protobuf.scheduler.inbound.RequestInstanceStateUpdate(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      context.parent ! service.ClusterManager.ClusterCommand(clusterId,
          RktClusterManager.GetInstanceStatus(instanceId))
    case dit4c.protobuf.scheduler.inbound.StartInstance(instanceId, clusterId, imageUrl, clusterAccessPasses) =>
      import dit4c.scheduler.domain._
      log.info(s"Instance $instanceId requested on $clusterId using $imageUrl, with access passes:\n"+
          clusterAccessPasses.map(_.toByteArray).map(Base64.getEncoder.encodeToString).mkString("\n"))
      context.parent ! service.ClusterManager.ClusterCommand(clusterId,
          RktClusterManager.StartInstance(instanceId, imageUrl, portalUri))
    case dit4c.protobuf.scheduler.inbound.SaveInstance(instanceId, clusterId, saveHelperImageUrl, imageServer) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      context.parent ! service.ClusterManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId,
              Instance.Save(saveHelperImageUrl, imageServer)))
    case dit4c.protobuf.scheduler.inbound.DiscardInstance(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      context.parent ! service.ClusterManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId, Instance.Discard))
    case dit4c.protobuf.scheduler.inbound.ConfirmInstanceUpload(instanceId, clusterId) =>
      import dit4c.scheduler.domain._
      import dit4c.scheduler.domain.{instance => i}
      context.parent ! service.ClusterManager.ClusterCommand(clusterId,
          RktClusterManager.InstanceEnvelope(instanceId, Instance.ConfirmUpload))
    case dit4c.protobuf.scheduler.inbound.SignedMessageForScheduler(msg) =>
      log.info(s"Signed message received:\n$msg")
      context.actorOf(
          Props(classOf[SignedMessageProcessor], keyManager, msg))
    // Outbound
    case Instance.StatusReport(Instance.Errored, data: Instance.ErrorData) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(data.instanceId, pb.InstanceStateUpdate.InstanceState.ERRORED,
            data.errors.mkString("\n\n"), Some(pbTimestamp(Instant.now)))
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
        pb.InstanceStateUpdate(data.instanceId, pbState, "", Some(pbTimestamp(Instant.now)))
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
      data match {
        case data: Instance.StartData =>
          import dit4c.common.KeyHelpers._
          data.keys.foreach { keys =>
              val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.AllocatedInstanceKey(
                pb.AllocatedInstanceKey(data.instanceId, keys.armoredPgpPublicKeyBlock)))
              outbound ! toBinaryMessage(msg.toByteArray)
          }
        case _ => // No need to do anything
      }
    case msg: Cluster.GetStateResponse =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      Some(msg)
        .collect {
          case Cluster.Active(clusterId, displayName, supportsSave) =>
            pb.ClusterStateUpdate(
                clusterId,
                pb.ClusterStateUpdate.ClusterState.ACTIVE,
                displayName,
                supportsSave,
                Some(pbTimestamp(Instant.now)))
          case Cluster.Inactive(clusterId, displayName) =>
            pb.ClusterStateUpdate(
                clusterId,
                pb.ClusterStateUpdate.ClusterState.INACTIVE,
                displayName,
                false,
                Some(pbTimestamp(Instant.now)))
        }
        .map { msg =>
          pb.OutboundMessage(newMsgId,
              pb.OutboundMessage.Payload.ClusterStateUpdate(msg))
        }
        .foreach { msg =>
          outbound ! toBinaryMessage(msg.toByteArray)
        }
    case RktClusterManager.UnableToStartInstance(instanceId, reason) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(instanceId, pb.InstanceStateUpdate.InstanceState.ERRORED,
            reason, Some(pbTimestamp(Instant.now)))
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
    case RktClusterManager.UnknownInstance(instanceId) =>
      import dit4c.protobuf.scheduler.{outbound => pb}
      val msg = pb.OutboundMessage(newMsgId, pb.OutboundMessage.Payload.InstanceStateUpdate(
        pb.InstanceStateUpdate(instanceId, pb.InstanceStateUpdate.InstanceState.UNKNOWN,
            "", Some(pbTimestamp(Instant.now)))
      ))
      outbound ! toBinaryMessage(msg.toByteArray)
    case msg: dit4c.scheduler.api.AddNode if msg.sshHostKeyFingerprints.isEmpty =>
      log.error(s"Received add node request with no fingerprints: $msg")
    case dit4c.scheduler.api.AddNode(clusterId, host, port, username, sshHostKeyFingerprints) =>
      val bestFingerprint =
        (sshHostKeyFingerprints.filter(_.startsWith("SHA256:")) ++ sshHostKeyFingerprints).head
      val id =
        Seq(username, host, port.toString, bestFingerprint)
          .map(URLEncoder.encode(_, "UTF-8"))
          .mkString("_")
      context.parent ! ClusterManager.ClusterCommand(
          clusterId,
          RktClusterManager.AddRktNode(
            id,
            host,
            port,
            username,
            sshHostKeyFingerprints,
            "/var/lib/dit4c-rkt"))
    case dit4c.scheduler.api.CoolDownNodes(clusterId, sshHostKeyFingerprints) =>
      context.parent ! ClusterManager.ClusterCommand(
          clusterId,
          RktClusterManager.CoolDownRktNodes(sshHostKeyFingerprints))
    case dit4c.scheduler.api.DecommissionNodes(clusterId, sshHostKeyFingerprints) =>
      context.parent ! ClusterManager.ClusterCommand(
          clusterId,
          RktClusterManager.DecommissionRktNodes(sshHostKeyFingerprints))
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
    val scheme = Uri(registrationUrl).scheme match {
      case "ws" => "http"
      case "wss" => "https"
      case other => other
    }
    Uri(registrationUrl).copy(scheme = scheme, path = Uri.Path.Empty, rawQueryString = None, fragment = None).toString
  }

}