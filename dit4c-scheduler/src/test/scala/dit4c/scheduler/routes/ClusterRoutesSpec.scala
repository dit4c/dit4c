package dit4c.scheduler.routes

import dit4c.scheduler.Specs2RouteTest
import org.specs2.matcher.JsonMatchers
import org.specs2.execute.Result
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import org.specs2.matcher.JsonType
import dit4c.scheduler.service.ClusterAggregateManager
import akka.actor.Props
import org.specs2.ScalaCheck
import akka.http.scaladsl.model.StatusCodes
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalacheck.Arbitrary
import akka.http.scaladsl.model.Uri
import org.scalacheck.Gen
import org.specs2.scalacheck.Parameters

class ClusterRoutesSpec extends Specs2RouteTest
    with JsonMatchers with ScalaCheck with PlayJsonSupport {

  val clusterAggregateManager = system.actorOf(Props[ClusterAggregateManager])
  val clusterRoutes = (new ClusterRoutes(clusterAggregateManager)).routes
  val basePath: Uri.Path = Uri.Path / "clusters"

  implicit def path2uri(path: Uri.Path) = Uri(path=path)

  "ClusterRoutes" >> {

    "default cluster" >> {

      "exists" >> {
        Get(basePath / "default") ~> clusterRoutes ~> check {
          (status must beSuccess) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> "default")
            /("type" -> "rkt")
          })
        }
      }
    }
    "not have any other random clusters" >> prop({ id: String =>
      Get(basePath / id) ~> clusterRoutes ~> check {
        status must be_==(StatusCodes.NotFound)
      }
    }).setGen(Gen.oneOf(
        Gen.alphaStr,
        Arbitrary.arbString.arbitrary).suchThat(!_.isEmpty))

  }

}