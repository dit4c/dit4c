package dit4c.scheduler.domain

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorSystem
import org.scalacheck.Gen
import dit4c.scheduler.ScalaCheckHelpers
import akka.testkit.TestProbe
import org.specs2.matcher.Matcher
import scala.util.Random
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import scala.concurrent.Future
import java.security.KeyPairGenerator
import java.security.SecureRandom
import dit4c.scheduler.runner.RktRunner
import java.nio.file.Paths

class ClusterAggregateSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import ClusterAggregate._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))
  val defaultConfigProvider = new DefaultConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(
          Paths.get("/var/lib/dit4c-rkt"),
          "dit4c-instance-",
          "" /* Not used */,
          "" /* Not used */)
  }

  "ClusterAggregate" >> {

    "GetState" >> {

      "initially returns Uninitialized" >> {
        // No state change between tests
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetState-Uninitialized")

        prop({ aggregateId: String =>
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId, defaultConfigProvider))
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[ClusterAggregate.State] must {
            be(ClusterAggregate.Uninitialized)
          }
        }).setGen(genAggregateId)
      }

      "returns type after being initialized" >> prop(
        (id: String, t: ClusterTypes.Value, system: ActorSystem) => {
          implicit val _ = system
          val aggregateId = s"somePrefix-$id"
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId, defaultConfigProvider));
          {
            probe.send(clusterAggregate, Initialize(t))
            probe.receiveOne(1.second)
            probe.sender must be(clusterAggregate)
          } and
          {
            import scala.language.experimental.macros
            probe.send(clusterAggregate, GetState)
            probe.expectMsgType[ClusterAggregate.ClusterType] must {
              be_==(t)
            }
          }
        }
       ).setGen1(genAggregateId)
    }

    "Initialize" >> {

      "becomes initialized" >> prop(
        (id: String, t: ClusterTypes.Value, system: ActorSystem) => {
          implicit val _ = system
          val aggregateId = s"somePrefix-$id"
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId, defaultConfigProvider))
          // Get returned state after initialization and from GetState
          probe.send(clusterAggregate, Initialize(t))
          val response = probe.expectMsgType[State]
          probe.send(clusterAggregate, GetState)
          val clusterType = probe.expectMsgType[ClusterType]
          (response must_== Active) and
          (clusterType must_== t)
        }
      ).setGen1(genAggregateId)
    }
  }
}
