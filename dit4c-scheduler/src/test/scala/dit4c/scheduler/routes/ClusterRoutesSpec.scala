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
import akka.testkit.TestActorRef
import akka.actor.Actor
import dit4c.scheduler.domain.ClusterAggregate
import org.scalacheck.ArbitraryLowPriority
import dit4c.scheduler.ScalaCheckHelpers

class ClusterRoutesSpec extends Specs2RouteTest
    with JsonMatchers with PlayJsonSupport
    with ScalaCheck with ScalaCheckHelpers {

  val basePath: Uri.Path = Uri.Path / "clusters"
  implicit def path2uri(path: Uri.Path) = Uri(path=path)

  "ClusterRoutes" >> {

    "get cluster info" >> {

      // We never want an empty string for these checks
      implicit val arbString = Arbitrary(genNonEmptyString)

      "exists" >> prop { (id: String, t: ClusterAggregate.ClusterType) =>
        val clusterRoutes = (new ClusterRoutes(TestActorRef(
            fixedResponseCAM(ClusterAggregate.Cluster(id, t))))).routes
        Get(basePath / "default") ~> clusterRoutes ~> check {
          (status must beSuccess) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> id)
            /("type" -> t.toString)
          })
        }
      }

      "does not exist" >> prop { id: String =>
        val clusterRoutes = (new ClusterRoutes(TestActorRef(
            fixedResponseCAM(ClusterAggregate.Uninitialized)))).routes
        Get(basePath / id) ~> clusterRoutes ~> check {
          status must be_==(StatusCodes.NotFound)
        }
      }

    }

  }

  def fixedResponseCAM(s: ClusterAggregate.State) =
    new Actor {
      import ClusterAggregateManager._

      def receive = {
        case GetCluster(id) => sender ! s
      }
    }

}