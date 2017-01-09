package dit4c.scheduler.service

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import dit4c.scheduler.ScalaCheckHelpers
import akka.http.scaladsl.Http
import akka.actor._
import akka.testkit.TestProbe
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.WSProbe
import akka.stream.ActorMaterializer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.Promise
import akka.util.ByteString
import akka.http.scaladsl.model.Uri
import org.specs2.scalacheck.Parameters
import dit4c.scheduler.domain.Instance
import dit4c.protobuf.scheduler.outbound.OutboundMessage
import akka.http.scaladsl.model.StatusCodes
import org.bouncycastle.openpgp.PGPPublicKeyRing
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.directives.Credentials
import pdi.jwt.JwtJson
import pdi.jwt.algorithms.JwtAsymetricAlgorithm
import scala.util.Try
import java.security.interfaces.RSAPrivateKey
import pdi.jwt.JwtAlgorithm
import play.api.libs.json.Json
import dit4c.scheduler.domain.Cluster

class PortalMessageBridgeSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with ScalaCheckHelpers {

  implicit val system = ActorSystem("PortalMessageBridgeSpec")
  implicit val materializer = ActorMaterializer()
  implicit val params = Parameters(minTestsOk = 5)

  import ScalaCheckHelpers._

  "PortalMessageBridgeSpec" >> {

    "connection behaviour" >> {

      "connects to a websocket server" >> {
        newWithProbes { (wsProbe: WSProbe, _: TestProbe, _: ActorRef) =>
          wsProbe.sendMessage("Hello")
          done
        }
      }

      "terminates on server complete" >> {
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          parentProbe.watch(msgBridge)
          wsProbe.sendMessage("Hello")
          wsProbe.sendCompletion()
          parentProbe.expectTerminated(msgBridge)
          done
        }
      }

    }

    "incoming message handling" >> {

      "StartInstance" >> prop({ (msgId: String, instanceId: String, clusterId: String, imageUrl: Uri) =>
        import dit4c.protobuf.scheduler.{inbound => pb}
        import dit4c.scheduler.service.{ClusterManager => cam}
        import dit4c.scheduler.domain.{RktClusterManager => ram}
        val msg = pb.InboundMessage(randomMsgId,
            pb.InboundMessage.Payload.StartInstance(
                pb.StartInstance(randomInstanceId, clusterId, imageUrl.toString)))
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          wsProbe.sendMessage(ByteString(msg.toByteArray))
          parentProbe.expectMsgPF(5.seconds) {
            case cam.ClusterCommand(clusterId, cmd: ram.StartInstance) =>
              // success
              done
          }
        }
      })

      "DiscardInstance" >> prop({ (msgId: String, instanceId: String, clusterId: String) =>
        import dit4c.protobuf.scheduler.{inbound => pb}
        import dit4c.scheduler.service.{ClusterManager => cam}
        import dit4c.scheduler.domain.{RktClusterManager => ram}
        import dit4c.scheduler.domain.{instance => i}
        val msg = pb.InboundMessage(randomMsgId,
            pb.InboundMessage.Payload.DiscardInstance(
                pb.DiscardInstance(randomInstanceId, clusterId)))
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          wsProbe.sendMessage(ByteString(msg.toByteArray))
          parentProbe.expectMsgPF(5.seconds) {
            case cam.ClusterCommand(clusterId, ram.InstanceEnvelope(instanceId, Instance.Discard)) =>
              // Success
              done
          }
        }
      })

    }

    "outgoing message handling" >> {

      "InstanceStateUpdate" >> prop({ (msgId: String, instanceId: String, imageUrl: Uri, portalUri: Uri) =>
        import dit4c.protobuf.scheduler.{outbound => pb}
        import dit4c.scheduler.service.{ClusterManager => cam}
        import dit4c.scheduler.domain.{RktClusterManager => ram}
        val dummyLocalImageId = "sha512-"+Stream.fill(64)("0").mkString
        val msgs: List[Instance.StatusReport] =
          Instance.StatusReport(Instance.WaitingForImage, Instance.StartData(
            instanceId, imageUrl.toString, None, portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Starting, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Running, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Stopping, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Exited, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Saving, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Saved, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Uploading, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Uploaded, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Discarded, Instance.StartData(
            instanceId, imageUrl.toString,
            Some(dummyLocalImageId), portalUri.toString, None)) ::
          Instance.StatusReport(Instance.Errored, Instance.ErrorData(
            instanceId, List("A bunch", "of errors", "occurred"))) ::
          Nil
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          msgs must contain(beLike[Instance.StatusReport] {
            case msg =>
              parentProbe.send(msgBridge, msg)
              val wsMsg = wsProbe.expectMessage()
              val om = OutboundMessage.parseFrom(wsMsg.asBinaryMessage.getStrictData.toArray)
              (om.messageId must {
                haveLength[String](32) and
                beMatching("[0-9a-f]+")
              }) and
              (om.payload.instanceStateUpdate.isDefined must beTrue)
          }).foreach
        }
      })
    }

  }

  def randomMsgId = Random.alphanumeric.take(20).mkString
  def randomInstanceId = Random.alphanumeric.take(20).mkString

  def newWithProbes[A](f: (WSProbe, TestProbe, ActorRef) => A): A = {
    import dit4c.common.KeyHelpers._
    val parentProbe = TestProbe()
    val keyManagerProbe = TestProbe()
    val wsProbe = WSProbe()
    val closePromise = Promise[Unit]()
    val secretKeyRing =
      parseArmoredSecretKeyRing({
        import scala.sys.process._
        this.getClass.getResource("unit_test_scheduler_keys.asc").cat.!!
      }).right.get
    val publicKeyInfo = KeyManager.PublicKeyInfo(
        PGPFingerprint("28D6BE5749FA9CD2972E3F8BAD0C695EF46AFF94"),
        secretKeyRing.toPublicKeyRing.armored)
    val route = Route.seal {
      // Source which never emits an element, and terminates on closePromise success
      val closeSource = Source.maybe[Message]
        .mapMaterializedValue { p => closePromise.future.foreach { _ => p.success(None) } }
      import akka.http.scaladsl.server.Directives._
      logRequest("websocket-server") {
        path("ws") {
          post {
            fileUpload("keys") {
              case (metadata, byteSource) =>
                val f = byteSource.runFold(ByteString.empty)(_ ++ _)
                  .map(_.decodeString("utf8"))
                  .map(parseArmoredPublicKeyRing)
                onSuccess(f) {
                  case Right(pkr: PGPPublicKeyRing) =>
                    val fingerprint = pkr.getPublicKey.fingerprint
                    val nonce = Random.alphanumeric.take(20).mkString
                    val wrappedKeys = JwtJson.encode(
                        Json.obj("keys" -> pkr.armored))
                    val cookie = HttpCookie.fromPair(HttpCookiePair("keys", wrappedKeys))
                    setCookie(cookie) {
                      redirect(s"/ws/$fingerprint", StatusCodes.SeeOther)
                    }
                  case Left(msg) =>
                    complete(StatusCodes.BadRequest, msg)
                }
            }
          }
        } ~
        path("ws" / publicKeyInfo.keyFingerprint.string) {
          cookie("keys") { cookiePair =>
            authenticateOAuth2[String]("", {
              case Credentials.Missing => None
              case Credentials.Provided(token) =>
                val claim = Json.parse(JwtJson.decode(cookiePair.value).get.content)
                val keys = claim.\("keys").as[String]
                val pkr = parseArmoredPublicKeyRing(keys).right.get
                pkr.authenticationKeys.view
                  .flatMap(k => k.asJavaPublicKey.map((k.fingerprint, _)))
                  .flatMap { case (fingerprint, key) =>
                    JwtJson.decode(token, key).toOption.map(_ => fingerprint)
                  }
                  .headOption
                  .map(_.string)
            }) { authenticationKeyId =>
              handleWebSocketMessages(wsProbe.flow.merge(closeSource, true))
            }
          }
        }
      }
    }
    val binding = Await.result(Http().bindAndHandle(Route.handlerFlow(route), "localhost", 0), 2.seconds)
    try {
      val msgBridgeRef = parentProbe.childActorOf(
          Props(classOf[PortalMessageBridge],
            keyManagerProbe.ref,
            s"http://localhost:${binding.localAddress.getPort}/ws"),
          "portal-message-bridge")
      keyManagerProbe.expectMsg(KeyManager.GetPublicKeyInfo)
      keyManagerProbe.reply(publicKeyInfo)
      val claim = keyManagerProbe.expectMsgType[KeyManager.SignJwtClaim].claim
      val tokens =
        secretKeyRing.authenticationKeys
          .flatMap { pk =>
            Try(secretKeyRing.getSecretKey(pk.getFingerprint)).toOption
          }
          .flatMap(_.asJavaPrivateKey)
          .map {
            case k: RSAPrivateKey =>
              JwtJson.encode(claim, k, JwtAlgorithm.RS512)
          }
      keyManagerProbe.reply(KeyManager.SignedJwtTokens(tokens))
      parentProbe.expectMsg(ClusterManager.GetClusters)
      parentProbe.reply(Cluster.Active("default", "Default Cluster", true))
      wsProbe.expectMessage // Expect cluster state update
      // Run block
      f(wsProbe, parentProbe, msgBridgeRef)
    } finally {
      closePromise.success(()) // Close websocket server-side if still open
      binding.unbind
    }
  }


}