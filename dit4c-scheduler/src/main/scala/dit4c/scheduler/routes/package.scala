package dit4c.scheduler

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport

package object routes extends Directives with PlayJsonSupport {
  import play.api.libs.json._

  case class ZoneIndex(zones: Set[Zone])

  case class Zone(
      val id: String)

  implicit val writesZone = Json.writes[Zone]
  implicit val writesZoneIndex = Json.writes[ZoneIndex]

}