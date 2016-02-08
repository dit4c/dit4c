package dit4c.gatehouse.docker

import scala.concurrent.duration._
import akka.actor.Actor
import scala.collection.immutable.Queue
import akka.actor.ActorRef
import akka.event.LoggingReceive
import akka.event.Logging
import scala.util.{Success,Failure}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

class DockerIndexActor(dockerClient: DockerClient) extends Actor {
  import DockerClient.{ContainerEvent,ContainerPortMapping}
  import context.dispatcher
  val log = Logging(context.system, this)
  val tick =
    context.system.scheduler.schedule(1 minute, 1 minute, self, "tick")
  val cpec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  import DockerIndexActor._

  type ContainerId = String

  private object BecomeActive
  private object ResetEventFeed
  private case class DelayedQuery(sender: ActorRef, query: PortQuery)
  private case class UpdatePortIndex(index: Map[String, String])

  private case class AddMapping(mapping: ContainerPortMapping)
  private case class RemoveMapping(id: ContainerId)

  private var queue: Queue[DelayedQuery] = Queue.empty

  override def preStart = {
    pollDocker(Set.empty).foreach { _ =>
      self ! BecomeActive
    }
    self ! ResetEventFeed
  }

  // Enqueue until we've got some data
  val receive: Receive = respondWith(Set.empty, Queue.empty, false)

  // Respond using index
  def respondWith(
      mappings: Set[ContainerPortMapping],
      queue: Queue[DelayedQuery],
      active: Boolean): Receive = {
    val index = mappings.map(m => (m.containerName -> m.networkPort)).toMap;
    {
      case "tick" =>
        pollDocker(mappings.map(_.containerId))
      case ResetEventFeed =>
        val dest = self
        val (fStart, fStop) = dockerClient.events {
          case ContainerEvent(id, t) if t == "die" || t == "stop" =>
            dest ! RemoveMapping(id)
          case ContainerEvent(id, t) if t == "start" || t == "rename" =>
            lookupPort(id)
        }
        fStop.onComplete { case _ => ResetEventFeed }
      case BecomeActive =>
        queue.foreach(self ! _)
        context.become(respondWith(mappings, Queue.empty, true))
      case AddMapping(newMapping) =>
        log.info(s"Add mapping: $newMapping")
        context.become(respondWith(mappings + newMapping, queue, active))
      case RemoveMapping(removedContainerId) =>
        log.info(s"Remove mapping: $removedContainerId")
        context.become(respondWith(
            mappings.filterNot(_.containerId == removedContainerId),
            queue, active))
      case DelayedQuery(originalSender, PortQuery(containerName)) =>
        originalSender ! PortReply(index.get(containerName))
      case PortQuery(containerName) if active =>
        sender ! PortReply(index.get(containerName))
      case query: PortQuery if !active =>
        context.become(respondWith(
            mappings, queue :+ DelayedQuery(sender, query), active))
    }
  }

  private def lookupPort(containerId: String): Future[Unit] = {
    var dest = self
    dockerClient.containerPort(containerId)(cpec).map {
      case Some(mapping) => dest ! AddMapping(mapping)
      case None => dest ! RemoveMapping(containerId)
    }.recover {
      case _ => ()
    }
  }

  private def pollDocker(knownContainerIds: Set[ContainerId]): Future[Unit] = {
    dockerClient.containerIds.flatMap { (newContainerIds: Set[ContainerId]) =>
      val dest = self
      val futures = (newContainerIds diff knownContainerIds).map(lookupPort)
      (knownContainerIds diff newContainerIds).map { id =>
        dest ! RemoveMapping(id)
      }
      Future.sequence(futures).map(_ => ())
    }.recoverWith {
      case e: Throwable =>
        log.warning(s"Docker poll failed: $e\n${e.getStackTrace.toSeq}")
        pollDocker(knownContainerIds)
    }
  }

}

object DockerIndexActor {
  case class PortQuery(containerName: String)
  case class PortReply(port: Option[String])
}
