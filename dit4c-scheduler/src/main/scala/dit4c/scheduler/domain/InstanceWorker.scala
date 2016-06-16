package dit4c.scheduler.domain

object InstanceWorker {
  trait Command
  trait InstanceDirective extends Command
  case class Fetch(image: Instance.NamedImage) extends InstanceDirective
  case class Start(
      instanceId: String,
      image: Instance.LocalImage,
      callbackUrl: String) extends InstanceDirective
  case class Terminate(instanceId: String) extends InstanceDirective
}

trait InstanceWorker {
  import InstanceWorker._

}