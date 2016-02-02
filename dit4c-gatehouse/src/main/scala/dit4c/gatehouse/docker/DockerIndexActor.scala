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
  val maxWaitTicks = 30

  import DockerIndexActor._

  private case class DelayedQuery(sender: ActorRef, query: PortQuery)
  private case class UpdatePortIndex(index: Map[String, String])

  private var queue: Queue[DelayedQuery] = Queue.empty

  override def preStart = pollDocker

  // Enqueue until we've got some data
  val receive: Receive = {
    case "tick" =>
      pollDocker
    case UpdatePortIndex(newIndex) =>
      context.become(respondWith(newIndex, 0))
      log.info(s"Using new index: $newIndex")
    case query: PortQuery =>
      queue = queue enqueue DelayedQuery(sender, query)
  }

  // Respond using index
  def respondWith(index: Map[String, String], waiting: Int): Receive = {
    clearQueue;
    {
      case "tick" if waiting <= 0 =>
        pollDocker
        context.become(respondWith(index, waiting + 1))
      case "tick" if waiting > 0 =>
        log.info("waiting on Docker poll")
        // Increment wait ticks, but loop to zero if we've waited too long
        context.become(respondWith(index, (waiting + 1) % maxWaitTicks))
      case UpdatePortIndex(newIndex) =>
        context.become(respondWith(newIndex, 0))
        log.info(s"Using new index: $newIndex")
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
      case Success(m: Map[String, String]) =>
        self ! UpdatePortIndex(m)
      case Failure(e) =>
        log.warning(s"Docker poll failed: $e\n${e.getStackTrace.toSeq}")
    })
  }

}

object DockerIndexActor {
  case class PortQuery(containerName: String)
  case class PortReply(port: Option[String])
}
