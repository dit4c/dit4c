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
  import Cluster._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))
  val defaultConfigProvider = new ConfigProvider {
    override def rktRunnerConfig =
      RktRunner.Config(
          Paths.get("/var/lib/dit4c-rkt"),
          "dit4c-instance-",
          "" /* Not used */,
          "" /* Not used */,
          None,
          "" /* Not used */)
    override def sshKeys = ???
  }

  "ClusterAggregate" >> {

    "GetState" >> {

      "returns Uninitialized if no info provided" >> {
        // No state change between tests
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetState-Uninitialized")

        prop({ aggregateId: String =>
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(Cluster.props(None, defaultConfigProvider))
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[Cluster.GetStateResponse] must {
            be(Cluster.Uninitialized)
          }
        }).setGen(genAggregateId)
      }

      "returns Active if marked active" >> prop(
        (id: String, displayName: String, supportsSave: Boolean, system: ActorSystem) => {
          import scala.language.experimental.macros
          implicit val _ = system
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(
                Cluster.props(
                  Some(ClusterInfo(displayName, true, supportsSave)),
                  defaultConfigProvider),
                id)
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[GetStateResponse] must {
            be_==(Active(id, displayName, supportsSave))
          }
        }
      ).setGen1(Gen.identifier)

      "returns Inactive if not marked active" >> prop(
        (id: String, displayName: String, supportsSave: Boolean, system: ActorSystem) => {
          import scala.language.experimental.macros
          implicit val _ = system
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(
                Cluster.props(
                  Some(ClusterInfo(displayName, false, supportsSave)),
                  defaultConfigProvider),
                id)
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[GetStateResponse] must {
            be_==(Inactive(id, displayName))
          }
        }
      ).setGen1(Gen.identifier)
    }
  }
}
