package dit4c.scheduler

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import akka.http.scaladsl.model.StatusCodes

package object routes extends Directives with PlayJsonSupport {

  def swaggerRoutes = pathPrefix("swagger") {
    getFromResourceDirectory("swagger") ~
    pathSingleSlash(get(redirect("index.html", StatusCodes.PermanentRedirect)))
  }

}