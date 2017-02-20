package dit4c.scheduler.domain

import dit4c.scheduler.runner.RktRunner
import dit4c.scheduler.runner.RktPod

object InstanceWorker {
  trait Command extends BaseCommand
  sealed trait InstanceDirective extends Command
  case class Fetch(imageName: String) extends InstanceDirective
  case class Start(
      imageId: String,
      portalUri: String) extends InstanceDirective
  case object Stop extends InstanceDirective
  case object Discard extends InstanceDirective
  case object Save extends InstanceDirective
  case class Upload(
      helperImage: String,
      imageServer: String,
      portalUri: String) extends InstanceDirective
  case class Assert(instanceAssertion: InstanceAssertion) extends InstanceDirective

  sealed trait InstanceAssertion
  case object StillRunning extends InstanceAssertion

}

trait InstanceWorker {
  import InstanceWorker._

  def instanceId: String

}