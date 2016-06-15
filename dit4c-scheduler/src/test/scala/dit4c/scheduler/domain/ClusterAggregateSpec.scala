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

class ClusterAggregateSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import ClusterAggregate._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))

  "ClusterAggregate" >> {

    "GetState" >> {

      "initially returns Uninitialized" >> {
        // No state change between tests
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetState-Uninitialized")

        prop({ aggregateId: String =>
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId))
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[ClusterAggregate.State] must {
            be(ClusterAggregate.Uninitialized)
          }
        }).setGen(genAggregateId)
      }

      "returns state after being initialized" >> prop(
        (id: String, t: ClusterTypes.Value, system: ActorSystem) => {
          implicit val _ = system
          val aggregateId = s"somePrefix-$id"
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId));
          {
            probe.send(clusterAggregate, Initialize(id, t))
            probe.receiveOne(1.second)
            probe.sender must be(clusterAggregate)
          } and
          {
            import scala.language.experimental.macros
            probe.send(clusterAggregate, GetState)
            probe.expectMsgType[ClusterAggregate.Cluster] must {
              (be_==(id) ^^ { c: ClusterAggregate.Cluster => c.id }) and
              (be_==(t) ^^ { c: ClusterAggregate.Cluster => c.`type` })
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
            system.actorOf(ClusterAggregate.props(aggregateId))
          val beExpectedInitializedState: Matcher[AnyRef] = beLike[AnyRef] {
            case state: ClusterAggregate.Cluster =>
              (state.id must_== id) and
              (state.`type` must_== t)
          }
          // Get returned state after initialization and from GetState
          probe.send(clusterAggregate, Initialize(id, t))
          val initResponse = probe.expectMsgType[AnyRef]
          probe.send(clusterAggregate, GetState)
          val getStateResponse = probe.expectMsgType[AnyRef]
          // Test both
          (initResponse must beExpectedInitializedState) and
          (getStateResponse must beExpectedInitializedState)
        }
      ).setGen1(genAggregateId)
    }

    "GetRktNodeState" >> {

      "initially returns Uninitialized" >> {
        val id = "test"
        val aggregateId = "Cluster-"+id
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId))
        val probe = TestProbe()
        probe.send(clusterAggregate, Initialize(id, ClusterTypes.Rkt))
        probe.receiveOne(1.second)
        prop({ (rktNodeId: String) =>
          val probe = TestProbe()
          probe.send(clusterAggregate, GetRktNodeState(rktNodeId))
          probe.expectMsgType[RktNode.Data] must {
            be(RktNode.NoConfig)
          }
        }).setGen(Gen.listOfN(8, Gen.numChar).map(_.mkString))
      }

    }

    "AddRktNode" >> {
      "initializes RktNode with config" >> {
        import scala.language.experimental.macros
        val id = "test"
        val aggregateId = "Cluster-"+id
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        val hostPublicKey = randomPublicKey
        val clusterAggregate =
            system.actorOf(ClusterAggregate.props(
                aggregateId, mockFetchSshHostKey(hostPublicKey)))
        val probe = TestProbe()
        probe.send(clusterAggregate, Initialize(id, ClusterTypes.Rkt))
        probe.receiveOne(1.second)
        probe.send(clusterAggregate, AddRktNode(
            "169.254.42.34", 22, "testuser", "/var/lib/dit4c/rkt"))
        val response = probe.expectMsgType[ClusterAggregate.RktNodeAdded]
        probe.send(clusterAggregate, GetState)
        val clusterState = probe.expectMsgType[ClusterAggregate.RktCluster]
        ( clusterState.nodeIds must contain(response.nodeId) )
      }
    }

    "ConfirmRktNodeKeys" >> {
      "makes RktNode ready to connect" >> {
        import scala.language.experimental.macros
        val id = "test"
        val aggregateId = "Cluster-"+id
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        val clusterAggregate =
            system.actorOf(ClusterAggregate.props(
                aggregateId, mockFetchSshHostKey(randomPublicKey)))
        val probe = TestProbe()
        probe.send(clusterAggregate, Initialize(id, ClusterTypes.Rkt))
        probe.receiveOne(1.second)
        probe.send(clusterAggregate, AddRktNode(
            "169.254.42.64", 22, "testuser", "/var/lib/dit4c/rkt"))
        val ClusterAggregate.RktNodeAdded(nodeId) =
          probe.expectMsgType[ClusterAggregate.RktNodeAdded]
        probe.send(clusterAggregate, ConfirmRktNodeKeys(nodeId))
        val updatedConfig = probe.expectMsgType[RktNode.NodeConfig]
        ( updatedConfig.readyToConnect must beTrue )
      }
    }
  }

  def randomPublicKey: RSAPublicKey = {
    val sr = SecureRandom.getInstance("SHA1PRNG")
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512, sr)
    kpg.genKeyPair.getPublic.asInstanceOf[RSAPublicKey]
  }

  def mockFetchSshHostKey(
      pk: RSAPublicKey)(host: String, port: Int): Future[RSAPublicKey] =
        Future.successful(pk)

}
