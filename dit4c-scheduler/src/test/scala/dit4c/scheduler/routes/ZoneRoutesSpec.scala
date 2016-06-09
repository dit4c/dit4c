package dit4c.scheduler.routes

import dit4c.scheduler.Specs2RouteTest
import org.specs2.matcher.JsonMatchers
import org.specs2.execute.Result
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import org.specs2.matcher.JsonType
import dit4c.scheduler.service.ZoneAggregateManager
import akka.actor.Props
import org.specs2.ScalaCheck
import akka.http.scaladsl.model.StatusCodes
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalacheck.Arbitrary
import akka.http.scaladsl.model.Uri
import org.scalacheck.Gen
import org.specs2.scalacheck.Parameters

class ZoneRoutesSpec extends Specs2RouteTest
    with JsonMatchers with ScalaCheck with PlayJsonSupport {

  val zoneAggregateManager = system.actorOf(Props[ZoneAggregateManager])
  val zoneRoutes = (new ZoneRoutes(zoneAggregateManager)).routes
  val basePath: Uri.Path = Uri.Path / "zones"

  implicit def path2uri(path: Uri.Path) = Uri(path=path)

  "ZoneRoutes" >> {

    "get default zone" >> {
      Get(basePath / "default") ~> zoneRoutes ~> check {
        (status must beSuccess) and
        (Json.prettyPrint(entityAs[JsValue]) must {
          /("id" -> "default")
          /("type" -> "rkt")
        })
      }
    }

    "not have any other random zones" >> prop({ id: String =>
      Get(basePath / id) ~> zoneRoutes ~> check {
        status must be_==(StatusCodes.NotFound)
      }
    }).setGen(Gen.oneOf(
        Gen.alphaStr,
        Arbitrary.arbString.arbitrary).suchThat(!_.isEmpty))

  }

}