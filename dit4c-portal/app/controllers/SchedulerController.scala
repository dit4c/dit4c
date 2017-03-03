package controllers

import com.softwaremill.tagging._
import scala.concurrent.duration._
import play.api.mvc.Controller
import akka.util.Timeout
import play.api.mvc.Action
import domain._
import services.SchedulerSharder
import scala.concurrent._
import akka.actor._

class SchedulerController(
    val schedulerSharder: ActorRef @@ SchedulerSharder.type)(
        implicit system: ActorSystem, executionContext: ExecutionContext)
    extends Controller {

  import akka.pattern.ask

  val log = play.api.Logger(this.getClass)

  def schedulerPgpKeys(schedulerId: String) = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (schedulerSharder ? SchedulerSharder.Envelope(schedulerId, SchedulerAggregate.GetKeyFingerprint)).map {
      case SchedulerAggregate.CurrentKeyFingerprint(fingerprint) =>
        Redirect(routes.KeyRingController.get(fingerprint.string))
      case SchedulerAggregate.NoKeysAvailable =>
        NotFound
    }
  }

  def schedulerSshKeys(schedulerId: String) = Action.async { implicit request =>
    import dit4c.common.KeyHelpers._
    implicit val timeout = Timeout(1.minute)
    (schedulerSharder ? SchedulerSharder.Envelope(schedulerId, SchedulerAggregate.GetKeys)).map {
      case SchedulerAggregate.CurrentKeys(keyBlock) =>
        val authorizedKeys =
          parseArmoredPublicKeyRing(keyBlock).right.get
            .authenticationKeys
            .flatMap(_.asOpenSSH)
            .mkString("\n") + "\n"
        Ok(authorizedKeys)
      case SchedulerAggregate.NoKeysAvailable =>
        NotFound
    }
  }

}