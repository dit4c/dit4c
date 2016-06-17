package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import akka.actor._
import scala.util._
import java.security.interfaces.RSAPublicKey

class RktInstanceWorker(runner: RktRunner) extends Actor
    with ActorLogging with InstanceWorker {
  import InstanceWorker._

  import context.dispatcher

  override val receive: Receive = {
    case Fetch(image: Instance.NamedImage) =>
      val instance = sender
      runner.fetch(image.name).andThen {
        case Success(imageId) =>
          instance ! Instance.ReceiveImage(Instance.LocalImage(imageId))
        case Failure(e) =>
          replyWithError("Unable to fetch image", instance, e)
      }
    case Start(instanceId, Instance.LocalImage(imageId), callbackUrl) =>
      val instance = sender
      runner.start(instanceId, imageId, callbackUrl).andThen {
        case Success(key: RSAPublicKey) =>
          instance ! Instance.AssociateSigningKey(Instance.RSAPublicKey(key))
          instance ! Instance.ConfirmStart
        case Failure(e) =>
          replyWithError("Unable to start image", instance, e)
      }
    case Terminate(instanceId) =>
      val instance = sender
      runner.stop(instanceId).andThen {
        case Success(imageId) =>
          instance ! Instance.ConfirmTerminated
        case Failure(e) =>
          replyWithError("Unable to terminate image", instance, e)
      }
  }

  private def replyWithError(msg: String, instance: ActorRef, e: Throwable) {
    val exceptionStr = s"${e.getMessage} ${e.getStackTrace.toList}"
    log.error(s"$msg for ${instance.path.name} → $exceptionStr")
    instance ! Instance.Error(msg)
  }

}