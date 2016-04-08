package controllers

import javax.inject.Inject
import models.{EventDAO,Event}
import play.api.mvc.Controller
import providers.db.CouchDB
import play.api.libs.json.Json
import java.time.Instant
import scala.concurrent.Future
import play.api.mvc.Result
import play.api.mvc.AnyContent

class EventMonitoringController @Inject() (
    val db: CouchDB.Database,
    val eventDao: EventDAO)
    extends Controller with Utils {

  import play.api.libs.json._

  def listLoginEvents(from: Option[Instant], to: Option[Instant]) =
    asAdmin { request =>
      for {
        events <- eventDao.listLogins(from, to)
      } yield Ok(Json.toJson(events))
    }


  private implicit val writesLoginEvent: Writes[Event.Login] =
    Writes { event =>
      Json.obj(
          "event_id" -> event.id,
          "timestamp" -> event.timestamp,
          "identity" -> event.identity,
          "name" -> event.name,
          "email" -> event.email)
    }

  private def asAdmin(f: AuthenticatedRequest[AnyContent] => Future[Result]) =
    Authenticated.async { implicit request =>
      if (request.user.roles.contains("admin")) {
        f(request)
      } else {
        Future.successful(Forbidden("Not an admin"))
      }
    }




}
