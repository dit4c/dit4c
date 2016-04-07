package controllers

import javax.inject.Inject
import models.EventDAO
import play.api.mvc.Controller
import providers.db.CouchDB

class EventMonitoringController @Inject() (
    val db: CouchDB.Database,
    val eventDao: EventDAO)
    extends Controller with Utils {

}
