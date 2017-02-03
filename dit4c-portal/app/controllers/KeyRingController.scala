package controllers

import com.softwaremill.tagging._
import scala.concurrent.duration._
import play.api.mvc.Controller
import akka.util.Timeout
import play.api.mvc.Action
import domain.KeyRingAggregate
import services.KeyRingSharder
import scala.concurrent._
import akka.actor._
import dit4c.common.KeyHelpers.PGPFingerprint

class KeyRingController(
    val keySharder: ActorRef @@ KeyRingSharder.type)(
        implicit system: ActorSystem, executionContext: ExecutionContext)
    extends Controller {

  import akka.pattern.ask

  val log = play.api.Logger(this.getClass)

  def get(fingerprintStr: String) = Action.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    val kf = PGPFingerprint(fingerprintStr)
    (keySharder ? KeyRingSharder.Envelope(kf, KeyRingAggregate.GetKeys)).map {
      case KeyRingAggregate.CurrentKeyBlock(keys) =>
        Ok(keys).as("application/pgp-keys")
      case KeyRingAggregate.NoKeysAvailable =>
        NotFound
    }
  }

}