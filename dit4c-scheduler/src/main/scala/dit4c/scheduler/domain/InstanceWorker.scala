package dit4c.scheduler.domain

object InstanceWorker {
  sealed trait Command extends BaseCommand
  sealed trait InstanceDirective extends Command
  case class Fetch(imageName: String) extends InstanceDirective
  case class Start(
      instanceId: String,
      imageId: String,
      portalUri: String) extends InstanceDirective
  case class Stop(instanceId: String) extends InstanceDirective
  case class Discard(instanceId: String) extends InstanceDirective
  case class Save(instanceId: String) extends InstanceDirective
  case class Upload(instanceId: String,
      helperImage: String,
      imageServer: String,
      portalUri: String) extends InstanceDirective
}

trait InstanceWorker {
  import InstanceWorker._

}