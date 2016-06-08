package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import play.api.libs.json.Json
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import javax.ws.rs.Path
import io.swagger.annotations._

@Path("/zones")
@Api(value = "/zones", produces = "application/json")
class ZoneRoutes extends Directives with PlayJsonSupport {

  def routes = zoneListRoute

  @ApiOperation(value = "Get list of all compute zones",
      nickname = "getAllZones", httpMethod = "GET",
      response = classOf[ZoneRoutes.Zone], responseContainer = "Set")
  val zoneListRoute = pathPrefix("zones") {
    pathEndOrSingleSlash {
      get {
        complete(Json.obj("zones" -> Json.arr(
          Json.obj("id" -> "default")
        )))
      }
    }
  }

}

object ZoneRoutes {
  case class Zone(id: String)
}