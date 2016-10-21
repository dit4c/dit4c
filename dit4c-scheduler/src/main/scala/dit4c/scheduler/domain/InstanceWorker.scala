package dit4c.scheduler.domain

object InstanceWorker {
  sealed trait Command
  sealed trait InstanceDirective extends Command
  case class Fetch(image: Instance.NamedImage) extends InstanceDirective
  case class Start(
      instanceId: String,
      image: Instance.LocalImage,
      portalUri: String) extends InstanceDirective
  case class Stop(instanceId: String) extends InstanceDirective
  case class Discard(instanceId: String) extends InstanceDirective
  case class Save(instanceId: String) extends InstanceDirective
  case class Upload(instanceId: String,
      helperImage: Instance.NamedImage,
      imageServer: String,
      portalUri: String) extends InstanceDirective
}

trait InstanceWorker {
  import InstanceWorker._

}