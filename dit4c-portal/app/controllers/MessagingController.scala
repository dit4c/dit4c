package controllers

import com.softwaremill.tagging._
import play.api.Environment
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import play.api.mvc.RequestHeader
import scala.concurrent.Future
import services.SchedulerSharder
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import domain.SchedulerAggregate
import scala.concurrent.ExecutionContext

class MessagingController(
    environment: Environment,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)(implicit ec: ExecutionContext)
    extends Controller {

  implicit val timeout = Timeout(5.seconds)

  def schedulerSocket(schedulerId: String) = WebSocket { request: RequestHeader =>
    for {
      validationResponse <- schedulerSharder ? SchedulerSharder.Envelope(schedulerId,
          SchedulerAggregate.VerifyJwt(dummyJwt))
    } yield {
      validationResponse match {
        case SchedulerAggregate.ValidJwt =>
          Left(NotImplemented(""))
        case SchedulerAggregate.InvalidJwt(msg) =>
          Left(Forbidden(msg))
      }
    }
  }

  private val dummyJwt = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.e30."

}