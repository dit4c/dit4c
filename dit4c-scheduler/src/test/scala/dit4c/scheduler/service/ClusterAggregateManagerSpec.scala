package dit4c.scheduler.service

import akka.actor._
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import scala.concurrent.duration._
import dit4c.scheduler.domain.ClusterAggregate
import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import dit4c.scheduler.domain.ClusterTypes
import org.specs2.concurrent.ExecutionEnv
import akka.util.Timeout

class ClusterAggregateManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {


  implicit val system = ActorSystem("ClusterAggregateManagerSpec-system")
  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask


  import ClusterAggregateManager._

  val clusterAggregateManager = system.actorOf(Props[ClusterAggregateManager])

  "ClusterAggregateManager" >> {

    "default cluster" >> {

      "exists" >> {
        import scala.language.experimental.macros
        (clusterAggregateManager ? GetCluster("default")) must {
          beLike[Any] {
            case state: ClusterAggregate.Cluster =>
              state must matchA[ClusterAggregate.Cluster]
                .id(be_==("default"))
                .`type`(be(ClusterTypes.Rkt))
          }
        }.awaitFor(longerThanAkkaTimeout)
      }
    }
    "not have any other random clusters" >> prop({ id: String =>
      import scala.language.experimental.macros
        (clusterAggregateManager ? GetCluster(id)) must {
          be_==(ClusterAggregate.Uninitialized)
        }.awaitFor(longerThanAkkaTimeout)
    }).setGen(Gen.oneOf(
        Gen.alphaStr,
        Arbitrary.arbString.arbitrary).suchThat(!_.isEmpty))
  }

  private def longerThanAkkaTimeout(implicit timeout: Timeout): FiniteDuration =
    timeout.duration + 100.milliseconds
}