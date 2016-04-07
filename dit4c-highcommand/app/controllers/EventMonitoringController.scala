package controllers

import javax.inject.Inject
import models.{EventDAO,Event}
import play.api.mvc.Controller
import providers.db.CouchDB
import play.api.libs.json.Json
import java.time.Instant
import scala.concurrent.Future

class EventMonitoringController @Inject() (
    val db: CouchDB.Database,
    val eventDao: EventDAO)
    extends Controller with Utils {

  import play.api.libs.json._

  def listLoginEvents = Authenticated.async { implicit request =>
    if (request.user.roles.contains("admin")) {
      // TODO: Add range parameters
      for {
        events <- eventDao.listLogins(None, None)
      } yield Ok(Json.toJson(events))
    } else {
      Future.successful(Forbidden("Not an admin"))
    }
  }


  private implicit val writesLoginEvent: Writes[Event.Login] = Writes { event =>
    Json.obj(
        "timestamp" -> event.timestamp,
        "identity" -> event.identity,
        "name" -> event.name,
        "email" -> event.email)
  }



}
