package dit4c.gatehouse.docker

import scala.concurrent.Future
import scala.concurrent.Promise

import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import akka.util.Timeout.intToTimeout
import spray.http.Uri
import spray.util.pimpFuture

class DockerIndexActorSpec extends Specification with NoTimeConversions {
  import scala.concurrent.duration._

  val system = ActorSystem("testSystem")
  implicit val timeout = Timeout(100.millis)

  import spray.util.pimpFuture

  def client = new DockerClient(Uri("http://localhost:4243/")) {
    override def containerPorts: Future[Map[String, Int]] =
      Promise.successful(Map("foo" -> 43000, "bar" -> 43001)).future
  }

  "DockerIndexActor" should {

    "respond to queries" in {
      import DockerIndexActor._
      val index: ActorRef = system.actorOf(Props(classOf[DockerIndexActor], client))

      (index ask PortQuery("foo"))
        .mapTo[PortReply] must equalTo(PortReply(Some(43000)))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("bar"))
        .mapTo[PortReply] must equalTo(PortReply(Some(43001)))
        .await(retries = 2, timeout = 200.millis)

      (index ask PortQuery("doesnotexist"))
        .mapTo[PortReply] must equalTo(PortReply(None))
        .await(retries = 2, timeout = 200.millis)

      0 must_== 0
    }
  }
}


