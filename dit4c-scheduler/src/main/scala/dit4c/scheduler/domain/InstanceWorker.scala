package dit4c.scheduler.domain

object InstanceWorker {

  trait Command
  trait InstanceDirective extends Command
  case class Fetch(image: Instance.NamedImage) extends InstanceDirective
  case class Start(image: Instance.LocalImage) extends InstanceDirective
  case object Stop extends InstanceDirective
}

trait InstanceWorker {
  import InstanceWorker._

}