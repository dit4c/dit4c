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
import akka.actor.Props
import dit4c.scheduler.runner.RktRunner

class RktClusterManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import RktClusterManager._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))

  "ClusterAggregate" >> {

    "GetRktNodeState" >> {

      "initially returns Uninitialized" >> {
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        prop({ (managerPersistenceId: String, rktNodeId: String) =>
          val manager =
              system.actorOf(RktClusterManager.props, managerPersistenceId)
          val probe = TestProbe()
          probe.send(manager, GetRktNodeState(rktNodeId))
          probe.expectMsgType[RktNode.Data] must {
            be(RktNode.NoConfig)
          }
        }).setGens(Gen.identifier, Gen.listOfN(8, Gen.numChar).map(_.mkString))
      }

    }

    "AddRktNode" >> {
      "initializes RktNode with config" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        val hostPublicKey = randomPublicKey
        val manager =
            system.actorOf(
                RktClusterManager.props(mockRktRunnerFactory,
                    mockFetchSshHostKey(hostPublicKey)),
                managerPersistenceId)
        val probe = TestProbe()
        probe.send(manager, AddRktNode(
            "169.254.42.34", 22, "testuser", "/var/lib/dit4c/rkt"))
        val response = probe.expectMsgType[RktNodeAdded]
        probe.send(manager, ClusterManager.GetStatus)
        val clusterState = probe.expectMsgType[ClusterInfo]
        ( clusterState.nodeIds must contain(response.nodeId) )
      }
    }

    "ConfirmRktNodeKeys" >> {
      "makes RktNode ready to connect" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetRktNodeState-Uninitialized")
        val manager =
            system.actorOf(
                RktClusterManager.props(mockRktRunnerFactory,
                    mockFetchSshHostKey(randomPublicKey)),
                managerPersistenceId)
        val probe = TestProbe()
        probe.send(manager, AddRktNode(
            "169.254.42.64", 22, "testuser", "/var/lib/dit4c/rkt"))
        val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded]
        probe.send(manager, ConfirmRktNodeKeys(nodeId))
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


  def mockRktRunnerFactory(
      cd: RktNode.ServerConnectionDetails, dir: String): RktRunner =
    new RktRunner {
      def fetch(imageName: String): scala.concurrent.Future[String] = ???
      def start(instanceId: String,image: String,callbackUrl: java.net.URL): scala.concurrent.Future[java.security.interfaces.RSAPublicKey] = ???
      def stop(instanceId: String): scala.concurrent.Future[Unit] = ???
    }

  def mockFetchSshHostKey(
      pk: RSAPublicKey)(host: String, port: Int): Future[RSAPublicKey] =
        Future.successful(pk)

}
