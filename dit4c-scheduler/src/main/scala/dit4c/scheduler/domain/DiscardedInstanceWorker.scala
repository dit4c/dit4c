package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import akka.actor._
import scala.util._
import org.bouncycastle.openpgp.PGPPublicKeyRing

class DiscardedInstanceWorker(val instanceId: String) extends Actor
    with ActorLogging with InstanceWorker {
  import InstanceWorker._
  import RktInstanceWorker._

  import context.dispatcher

  override val receive: Receive = {
    case command: Command => receiveCmd(command)
  }

  private val offlineError = Instance.Error(s"Compute node for instance $instanceId is now permanently offline")

  protected def receiveCmd(command: Command): Unit = command match {
    case _: Fetch | _: Start | Save | _: Upload    =>  sender ! offlineError
    case Stop    =>  sender ! Instance.ConfirmExited
    case Discard =>  sender ! Instance.ConfirmDiscard
    case Done    =>  context.stop(context.self)
    case Assert(StillRunning) =>
      sender ! Instance.ConfirmExited
      sender ! offlineError
      self ! Done
    case Assert(StillExists) =>
      sender ! offlineError
      self ! Done
  }

}