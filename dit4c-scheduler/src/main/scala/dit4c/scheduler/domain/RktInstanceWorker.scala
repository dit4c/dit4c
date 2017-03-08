package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import akka.actor._
import scala.util._
import org.bouncycastle.openpgp.PGPPublicKeyRing

object RktInstanceWorker {
  import InstanceWorker._
  sealed trait NodeDirective extends Command
  case class CurrentInstanceState(
      state: RktRunner.InstanceState,
      timestamp: java.time.Instant) extends NodeDirective
}

class RktInstanceWorker(val instanceId: String, runner: RktRunner) extends Actor
    with ActorLogging with InstanceWorker {
  import InstanceWorker._
  import RktInstanceWorker._

  import context.dispatcher

  override val receive: Receive = {
    case command: Command => receiveCmd(command)
  }

  private var lastKnownState: Option[(RktRunner.InstanceState, java.time.Instant)] = None

  protected def receiveCmd(command: Command): Unit = command match {
    case Fetch(imageName: String) =>
      val instance = sender
      runner.fetch(imageName).andThen {
        case Success(imageId) =>
          instance ! Instance.ReceiveImage(imageId)
        case Failure(e) =>
          replyWithError("Unable to fetch image", instance, e)
      }
    case Start(imageId, callbackUrl) =>
      val instance = sender
      runner.start(instanceId, imageId, callbackUrl).andThen {
        case Success(key: PGPPublicKeyRing) =>
          instance ! Instance.AssociateKeys(Instance.InstanceKeys(key).armoredPgpPublicKeyBlock)
          instance ! Instance.ConfirmStart
        case Failure(e) =>
          replyWithError("Unable to start image", instance, e)
      }
    case Stop =>
      val instance = sender
      runner.stop(instanceId).andThen {
        case Success(imageId) =>
          instance ! Instance.ConfirmExited
        case Failure(e) =>
          replyWithError("Unable to terminate image", instance, e)
      }
    case Discard =>
      // TODO: Actually clean up instance
      sender ! Instance.ConfirmDiscard
    case Save =>
      val instance = sender
      runner.export(instanceId).andThen {
        case Success(_) =>
          instance ! Instance.ConfirmSaved
        case Failure(e) =>
          replyWithError("Unable to save image", instance, e)
      }
    case Upload(imageServer, portalUri) =>
      val instance = sender
      runner.uploadImage(instanceId, imageServer, portalUri).andThen {
        case Success(imageId) =>
          // We've simply started the upload process, so don't confirm the upload
          log.info(s"Upload sucessfully initiated for $instanceId")
        case Failure(e) =>
          replyWithError("Unable to upload image", instance, e)
      }
    case Done =>
      context.stop(context.self)
    case CurrentInstanceState(state, timestamp) =>
      lastKnownState match {
        case Some((previousState, _)) if state == previousState =>
          // Do nothing
        case _ =>
          lastKnownState = Some((state, timestamp))
      }
    case Assert(StillRunning) =>
      import dit4c.scheduler.runner.RktPod
      import java.time._
      lastKnownState match {
        case Some((RktPod.States.Exited, _)) =>
          log.warning(s"Instance $instanceId exited without user interaction")
          sender ! Instance.ConfirmExited
        case Some((RktPod.States.Unknown, timestamp)) if Instant.now.minus(Duration.ofMinutes(1)).isAfter(timestamp) =>
          log.warning(s"Instance $instanceId had unknown status for more than a minute - assuming permanently missing")
          sender ! Instance.ConfirmExited
          sender ! Instance.Error("Disappeared from compute node")
        case _ =>
          // Do nothing
      }
    case Assert(StillExists) =>
      import dit4c.scheduler.runner.RktPod
      import java.time._
      lastKnownState match {
        case Some((RktPod.States.Unknown, timestamp)) if Instant.now.minus(Duration.ofMinutes(1)).isAfter(timestamp) =>
          log.warning(s"Instance $instanceId had unknown status for more than a minute - assuming discarded")
          sender ! Instance.Discard
        case _ =>
          // Do nothing
      }
  }

  private def replyWithError(msg: String, instance: ActorRef, e: Throwable) {
    val exceptionStr = s"${e.getMessage} ${e.getStackTrace.toList}"
    log.error(s"$msg for ${instance.path.name} → $exceptionStr")
    instance ! Instance.Error(msg)
  }

}