package dit4c.scheduler.service

import akka.actor._
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import scala.concurrent.duration._
import dit4c.scheduler.domain.ClusterAggregate
import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import org.specs2.concurrent.ExecutionEnv
import akka.util.Timeout
import dit4c.scheduler.ScalaCheckHelpers
import akka.testkit.TestProbe

class ClusterAggregateManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with MatcherMacros
    with ScalaCheck with ScalaCheckHelpers {

  implicit val system = ActorSystem("ClusterAggregateManagerSpec")

  import ScalaCheckHelpers._
  import ClusterAggregateManager._

  val clusterAggregateManager = system.actorOf(Props[ClusterAggregateManager])

  "ClusterAggregateManager" >> {

    "default cluster" >> {

      "exists" >> {
        import scala.language.experimental.macros
        val probe = TestProbe()
        probe.send(clusterAggregateManager, GetCluster("default"))
        probe.expectMsgType[ClusterAggregate.RktCluster] must {
          matchA[ClusterAggregate.RktCluster]
            .id(be_==("default"))
        }
      }
    }

    "not have any other random clusters" >> prop({ id: String =>
      val probe = TestProbe()
      probe.send(clusterAggregateManager, GetCluster(id))
      probe.expectMsgType[ClusterAggregate.State] must {
        be_==(ClusterAggregate.Uninitialized)
      }
    }).setGen(genNonEmptyString)
  }

  private def longerThanAkkaTimeout(implicit timeout: Timeout): FiniteDuration =
    timeout.duration + 100.milliseconds
}