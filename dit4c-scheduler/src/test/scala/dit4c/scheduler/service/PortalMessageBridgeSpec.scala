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
        import dit4c.scheduler.service.{ClusterAggregateManager => cam}
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
        import dit4c.scheduler.service.{ClusterAggregateManager => cam}
        import dit4c.scheduler.domain.{RktClusterManager => ram}
        val msg = pb.InboundMessage(randomMsgId,
            pb.InboundMessage.Payload.DiscardInstance(
                pb.DiscardInstance(randomInstanceId, clusterId)))
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          wsProbe.sendMessage(ByteString(msg.toByteArray))
          parentProbe.expectMsgPF(5.seconds) {
            case cam.ClusterCommand(clusterId, ram.TerminateInstance(instanceId)) =>
              // Success
              done
          }
        }
      })

    }

    "outgoing message handling" >> {

      "InstanceStateUpdate" >> prop({ (msgId: String, instanceId: String, imageUrl: Uri, portalUri: Uri) =>
        import dit4c.protobuf.scheduler.{outbound => pb}
        import dit4c.scheduler.service.{ClusterAggregateManager => cam}
        import dit4c.scheduler.domain.{RktClusterManager => ram}
        val msg = Instance.StatusReport(Instance.WaitingForImage, Instance.StartData(
            instanceId, Instance.NamedImage(imageUrl.toString), None, portalUri.toString, None))
        newWithProbes { (wsProbe: WSProbe, parentProbe: TestProbe, msgBridge: ActorRef) =>
          parentProbe.send(msgBridge, msg)
          wsProbe.expectMessage() match {
            case wsMsg =>
              val om = OutboundMessage.parseFrom(wsMsg.asBinaryMessage.getStrictData.toArray)
              (om.messageId must {
                haveLength[String](32) and
                beMatching("[0-9a-f]+")
              }) and
              (om.payload.instanceStateUpdate.isDefined must beTrue)
          }
        }
      })
    }

  }

  def randomMsgId = Random.alphanumeric.take(20).mkString
  def randomInstanceId = Random.alphanumeric.take(20).mkString

  def newWithProbes[A](f: (WSProbe, TestProbe, ActorRef) => A): A = {
    val parentProbe = TestProbe()
    val wsProbe = WSProbe()
    val closePromise = Promise[Unit]()
    val route = Route.seal {
      // Source which never emits an element, and terminates on closePromise success
      val closeSource = Source.maybe[Message]
        .mapMaterializedValue { p => closePromise.future.foreach { _ => p.success(None) } }
      import akka.http.scaladsl.server.Directives._
      logRequest("websocket-server") {
        path("ws") {
          handleWebSocketMessages(wsProbe.flow.merge(closeSource, true))
        }
      }
    }
    val binding = Await.result(Http().bindAndHandle(Route.handlerFlow(route), "localhost", 0), 2.seconds)
    try {
      val msgBridgeRef = parentProbe.childActorOf(Props(classOf[PortalMessageBridge],
        s"ws://localhost:${binding.localAddress.getPort}/ws"),
        "portal-message-bridge")
      f(wsProbe, parentProbe, msgBridgeRef)
    } finally {
      closePromise.success(()) // Close websocket server-side if still open
      binding.unbind
    }
  }


}