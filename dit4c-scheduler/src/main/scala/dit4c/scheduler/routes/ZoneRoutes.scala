package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import play.api.libs.json.Json
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport


class ZoneRoutes extends Directives with PlayJsonSupport {

  def routes = zoneListRoute

  val zoneListRoute = pathPrefix("zones") {
    pathEndOrSingleSlash {
      get {
        complete(Json.obj("zones" -> Json.arr()))
      }
    }
  }

}