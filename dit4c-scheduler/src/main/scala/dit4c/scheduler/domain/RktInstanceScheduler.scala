package dit4c.scheduler.domain

import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import akka.actor.ActorLogging

object RktInstanceScheduler {

  sealed trait Response
  case class WorkerFound(nodeId: String, worker: ActorRef) extends Response
  case object NoWorkersAvailable extends Response

}

class RktInstanceScheduler(
    nodes: Map[String, ActorRef],
    completeWithin: FiniteDuration,
    requireWorker: Boolean) extends Actor with ActorLogging {

  import RktInstanceScheduler._
  private object TimedOut

  override def preStart = {
    implicit val ec = context.dispatcher
    sequentiallyCheckNodes(Random.shuffle(nodes.keys.toList))
    context.system.scheduler.scheduleOnce(completeWithin, self, TimedOut)
  }

  override val receive: Receive = {
    case _ => // Never uses this handler - always set by check
  }

  def sequentiallyCheckNodes(remainingNodesToTry: List[String]) {
    remainingNodesToTry match {
      case nodeId :: rest =>
        // Ready to receive response
        context.become({
          case RktNode.WorkerCreated(worker) =>
            context.parent ! WorkerFound(nodeId, worker)
            context.stop(self)
          case TimedOut =>
            log.warning(
                s"Unable to assign instance worker within $completeWithin")
            giveUpAndStop
          case RktNode.UnableToProvideWorker(msg) =>
            sequentiallyCheckNodes(rest)
        })
        // Query next Node
        log.info(s"Requesting instance worker from node $nodeId")
        nodes(nodeId) ! nodeRequest
      case Nil =>
        log.warning(
            s"All nodes refused to provide an instance worker")
        giveUpAndStop
    }
  }

  def giveUpAndStop {
    context.parent ! NoWorkersAvailable
    context.stop(self)
  }

  private val nodeRequest =
    if (this.requireWorker) RktNode.RequireInstanceWorker
    else RktNode.RequestInstanceWorker

}