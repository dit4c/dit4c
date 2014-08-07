package dit4c.gatehouse.docker

import scala.concurrent.duration._
import akka.actor.Actor
import scala.collection.immutable.Queue
import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.event.Logging
import scala.util.{Success,Failure}

class DockerIndexActor(dockerClient: DockerClient) extends Actor {
  import context.dispatcher
  val log = Logging(context.system, this)
  val tick =
    context.system.scheduler.schedule(1000 millis, 1000 millis, self, "tick")

  import DockerIndexActor._

  private case class DelayedQuery(sender: ActorRef, query: PortQuery)
  private case class UpdatePortIndex(index: Map[String, Int])

  private var queue: Queue[DelayedQuery] = Queue.empty

  override def preStart = pollDocker

  // Common Receive logic
  private val commonReceive: Receive = {
    case "tick" =>
      pollDocker
    case UpdatePortIndex(newIndex) =>
      context.become(respondWith(newIndex))
  }

  // Enqueue until we've got some data
  val receive: Receive = commonReceive orElse {
    case query: PortQuery =>
      queue = queue enqueue DelayedQuery(sender, query)
  }

  // Respond using index
  def respondWith(index: Map[String, Int]): Receive = {
    clearQueue
    commonReceive orElse {
      case DelayedQuery(originalSender, PortQuery(containerName)) =>
        originalSender ! PortReply(index.get(containerName))
      case PortQuery(containerName) =>
        sender ! PortReply(index.get(containerName))
    }
  }

  private def clearQueue = {
    queue.foreach(self ! _)
    queue = Queue.empty
  }

  private def pollDocker = {
    dockerClient.containerPorts.onComplete({
      case Success(m: Map[String, Int]) =>
        self ! UpdatePortIndex(m)
      case Failure(e) =>
        log.warning(s"Docker poll failed: $e")
    })
  }

}

object DockerIndexActor {
  case class PortQuery(containerName: String)
  case class PortReply(port: Option[Int])
}