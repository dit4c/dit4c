package dit4c.scheduler.service

import akka.actor._
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import scala.concurrent.duration._
import dit4c.scheduler.domain.Cluster
import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import org.specs2.concurrent.ExecutionEnv
import akka.util.Timeout
import dit4c.scheduler.ScalaCheckHelpers
import akka.testkit.TestProbe
import dit4c.scheduler.domain.ConfigProvider
import dit4c.scheduler.runner.RktRunner
import java.nio.file.Paths
import dit4c.scheduler.domain.ClusterInfo

class ClusterAggregateManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with ScalaCheckHelpers {

  implicit val system = ActorSystem("ClusterAggregateManagerSpec")

  import ScalaCheckHelpers._
  import dit4c.scheduler.domain.Cluster._
  import ClusterManager._

  val defaultConfigProvider = new ConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(
          Paths.get("/var/lib/dit4c-rkt"),
          "dit4c-instance-",
          "" /* Not used */,
          "" /* Not used */)
  }
  val clusters: Map[String, ClusterInfo] = Map(
      "default" -> ClusterInfo("Default Cluster", true, true))
  val clusterAggregateManager = system.actorOf(
      Props(
          classOf[ClusterManager],
          defaultConfigProvider,
          clusters
      )
    )

  "ClusterAggregateManager" >> {

    "default cluster" >> {

      "exists" >> {
        val probe = TestProbe()
        probe.send(clusterAggregateManager, GetCluster("default"))
        probe.expectMsgType[GetStateResponse] must {
          be_==(Cluster.Active(
              clusters("default").displayName,
              clusters("default").supportsSave))
        }
      }

      "can receive wrapped messages" >> {
        val probe = TestProbe()
        probe.send(clusterAggregateManager,
            ClusterCommand("default", Cluster.GetState))
        probe.expectMsgType[GetStateResponse] must {
          be_==(Cluster.Active(
              clusters("default").displayName,
              clusters("default").supportsSave))
        }
      }
    }

    "not have any other random clusters" >> prop({ id: String =>
      val probe = TestProbe()
      probe.send(clusterAggregateManager, GetCluster(id))
      probe.expectMsgType[GetStateResponse] must {
        be_==(Cluster.Uninitialized)
      }
    }).setGen(genNonEmptyString)
  }

  private def longerThanAkkaTimeout(implicit timeout: Timeout): FiniteDuration =
    timeout.duration + 100.milliseconds
}