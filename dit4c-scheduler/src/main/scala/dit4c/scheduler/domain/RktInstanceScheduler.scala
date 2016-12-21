package dit4c.scheduler.domain

import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import akka.actor.ActorLogging
import akka.actor.TypedActor.PostStop
import akka.actor.Cancellable
import akka.actor.Props

object RktInstanceScheduler {

  def props(
      instanceId: String,
      nodes: Set[ActorRef],
      completeWithin: FiniteDuration) =
    Props(classOf[RktInstanceScheduler],
        instanceId,
        nodes,
        completeWithin)

  sealed trait Command extends BaseCommand
  protected case object TimedOut extends Command

  sealed trait Response extends BaseResponse
  case class WorkerFound(worker: ActorRef) extends Response
  case object NoWorkersAvailable extends Response

}

class RktInstanceScheduler(
    instanceId: String,
    nodes: Set[ActorRef],
    completeWithin: FiniteDuration) extends Actor with ActorLogging {

  import RktInstanceScheduler._

  var cancelTimeout: Option[Cancellable] = None

  override def preStart = {
    implicit val ec = context.dispatcher
    cancelTimeout = Some(context.system.scheduler.scheduleOnce(completeWithin, self, TimedOut))
    queryAllForExisting
  }

  override def postStop = {
    cancelTimeout.foreach(_.cancel)
  }

  // Never used
  override val receive: Receive = PartialFunction.empty

  private def queryAllForExisting: Unit = {
    nodes.foreach { node => node ! RktNode.GetWorkerForExistingInstance(instanceId) }
    waitForExisting(nodes)
  }

  private def waitForExisting(remainingNodes: Set[ActorRef]): Unit =
    if (remainingNodes.isEmpty) {
      sequentiallyCheckNodes(Random.shuffle(nodes.toList))
    } else {
      context.become({
        case RktNode.WorkerCreated(worker) =>
          val node = sender
          context.parent ! WorkerFound(worker)
          log.info(s"Instance worker provided by $node")
          context.stop(self)
        case TimedOut =>
          log.warning(
              s"Unable to assign instance worker within $completeWithin")
          giveUpAndStop
        case RktNode.UnableToProvideWorker(msg) =>
          waitForExisting(remainingNodes - sender)
      })
    }

  private def sequentiallyCheckNodes(remainingNodesToTry: List[ActorRef]): Unit =
    remainingNodesToTry match {
      case node :: rest =>
        // Ready to receive response
        context.become({
          case RktNode.WorkerCreated(worker) =>
            context.parent ! WorkerFound(worker)
            log.info(s"Instance worker provided by $node")
            context.stop(self)
          case TimedOut =>
            log.warning(
                s"Unable to assign instance worker within $completeWithin")
            giveUpAndStop
          case RktNode.UnableToProvideWorker(msg) =>
            log.info(msg)
            sequentiallyCheckNodes(rest)
        })
        // Query next Node
        log.info(s"Requesting instance worker from node $node")
        node ! RktNode.GetWorkerForNewInstance(instanceId)
      case Nil =>
        log.warning(
            s"All nodes refused to provide an instance worker")
        giveUpAndStop
    }

  private def giveUpAndStop {
    context.parent ! NoWorkersAvailable
    context.stop(self)
  }
}