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

class PortalMessageBridgeSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with ScalaCheckHelpers {

  implicit val system = ActorSystem("PortalMessageBridgeSpec")
  implicit val materializer = ActorMaterializer()

  import ScalaCheckHelpers._

  "PortalMessageBridgeSpec" >> {

    "connects to a websocket server" >> {
      newWithProbes { (wsProbe: WSProbe, camProbe: TestProbe) =>
        wsProbe.sendMessage("Hello")
        done
      }
    }
  }

  def newWithProbes[A](f: (WSProbe, TestProbe) => A): A = {
    val camProbe = TestProbe()
    val wsProbe = WSProbe()
    val route = Route.seal {
      import akka.http.scaladsl.server.Directives._
      logRequest("websocket-server") {
        path("ws") {
          handleWebSocketMessages(wsProbe.flow)
        }
      }
    }
    val binding = Await.result(Http().bindAndHandle(Route.handlerFlow(route), "localhost", 0), 2.seconds)
    try {
      val msgBridge = system.actorOf(Props(classOf[PortalMessageBridge],
        s"ws://localhost:${binding.localAddress.getPort}/ws", camProbe.ref),
        "portal-message-bridge-"+Random.alphanumeric.take(10).mkString)
      f(wsProbe, camProbe)
    } finally {
      binding.unbind
    }
  }


}