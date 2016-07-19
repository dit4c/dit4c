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
import dit4c.scheduler.domain.DefaultConfigProvider
import dit4c.scheduler.runner.RktRunner
import java.nio.file.Paths

class ClusterAggregateManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with ScalaCheckHelpers {

  implicit val system = ActorSystem("ClusterAggregateManagerSpec")

  import ScalaCheckHelpers._
  import ClusterAggregateManager._

  val defaultConfigProvider = new DefaultConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(Paths.get("/var/lib/dit4c-rkt"), "dit4c-instance-")
  }
  val clusterAggregateManager = system.actorOf(Props(classOf[ClusterAggregateManager], defaultConfigProvider))

  "ClusterAggregateManager" >> {

    "default cluster" >> {

      "exists" >> {
        val probe = TestProbe()
        probe.send(clusterAggregateManager, GetCluster("default"))
        probe.expectMsgType[ClusterAggregate.ClusterType] must {
          be_==(ClusterAggregate.ClusterTypes.Rkt)
        }
      }

      "can receive wrapped messages" >> {
        val probe = TestProbe()
        probe.send(clusterAggregateManager,
            ClusterCommand("default", ClusterAggregate.GetState))
        probe.expectMsgType[ClusterAggregate.ClusterType] must {
          be_==(ClusterAggregate.ClusterTypes.Rkt)
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