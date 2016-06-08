package dit4c.scheduler.routes

import dit4c.scheduler.Specs2RouteTest
import org.specs2.matcher.JsonMatchers
import org.specs2.execute.Result
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import org.specs2.matcher.JsonType

class ZoneRoutesSpec extends Specs2RouteTest with JsonMatchers {

  val zoneRoutes = (new ZoneRoutes).routes

  "ZoneRoutes" >> {

    "list zones" >> {
      Result.foreach(Seq("/zones", "/zones/")) { path =>
        Get(path) ~> zoneRoutes ~> check {
          Json.prettyPrint(entityAs[JsValue]) must {
            /("zones") /{
              /("id" -> "default")
            }
          }
        }
      }
    }

  }

}