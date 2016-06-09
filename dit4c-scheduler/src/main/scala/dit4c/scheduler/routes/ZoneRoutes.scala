package dit4c.scheduler.routes

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import javax.ws.rs.Path
import io.swagger.annotations._

@Path("/zones")
@Api(value = "/zones", description="compute zones", produces = "application/json")
class ZoneRoutes extends Directives with PlayJsonSupport {

  def routes = zoneListRoute

  @ApiOperation(value = "Get list of all compute zones",
      httpMethod = "GET",
      response = classOf[ZoneIndex])
  val zoneListRoute = pathPrefix("zones") {
    pathEndOrSingleSlash {
      get {
        complete(ZoneIndex(Set(Zone("default"))))
      }
    }
  }

}