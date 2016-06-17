package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import akka.actor.Actor
import scala.util._
import java.security.interfaces.RSAPublicKey

class RktInstanceWorker(runner: RktRunner) extends Actor with InstanceWorker {
  import InstanceWorker._

  import context.dispatcher

  override val receive: Receive = {
    case Fetch(image: Instance.NamedImage) =>
      val instance = sender
      runner.fetch(image.name).andThen {
        case Success(imageId) =>
          instance ! Instance.ReceiveImage(Instance.LocalImage(imageId))
        case Failure(e) =>
          instance ! error("Unable to fetch image", e)
      }
    case Start(instanceId, Instance.LocalImage(imageId), callbackUrl) =>
      val instance = sender
      runner.start(instanceId, imageId, callbackUrl).andThen {
        case Success(key: RSAPublicKey) =>
          instance ! Instance.AssociateSigningKey(Instance.RSAPublicKey(key))
          instance ! Instance.ConfirmStart
        case Failure(e) =>
          instance ! error("Unable to start image", e)
      }
    case Terminate(instanceId) =>
      val instance = sender
      runner.stop(instanceId).andThen {
        case Success(imageId) =>
          instance ! Instance.ConfirmTerminated
        case Failure(e) =>
          instance ! error("Unable to terminate image", e)
      }
  }

  private def error(msg: String, e: Throwable): Instance.Error =
    Instance.Error(s"$msg → ${e.getMessage} ${e.getStackTrace.toList}")


}