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
import dit4c.scheduler.domain.Instance.NamedImage
import akka.actor.Terminated

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
          ActorSystem("RktClusterManager-GetRktNodeState-Uninitialized")
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
        implicit val system = ActorSystem("RktClusterManager-AddRktNode")
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
          ActorSystem("RktClusterManager-ConfirmRktNodeKeys")
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

    "StartInstance" >> {
      "starts an instance" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-StartInstance-start")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  callbackUrl: String): Future[RSAPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
            }

        val manager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomPublicKey)),
                managerPersistenceId)
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded]
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig]
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance]
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport]
          instanceStatus.data must beLike {
            case Instance.StartData(id, providedImage, _, callback, _) =>
              ( id must be_==(response.instanceId) ) and
              ( providedImage must be_==(testImage) ) and
              ( callback must be_==(testCallback) )
          }
        }
      }

      "instance exists after system restart" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-StartInstance-restart")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  callbackUrl: String): Future[RSAPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
            }

        def createManager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomPublicKey)),
                managerPersistenceId)
        val manager = createManager
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded]
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig]
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance]
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.watch(manager)
          probe.send(manager, Shutdown)
          probe.expectMsgType[Terminated]
          val newManager = createManager
          probe.send(newManager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport]
          instanceStatus.data must beLike {
            case Instance.StartData(id, providedImage, _, callback, key) =>
              ( id must be_==(response.instanceId) ) and
              ( providedImage must be_==(testImage) ) and
              ( callback must be_==(testCallback) ) and
              ( key must beSome[Instance.InstanceSigningKey] )
          }
        }
      }
    }

    "TerminateInstance" >> {
      "terminates an instance" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-TerminateInstance")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  callbackUrl: String): Future[RSAPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] =
                Future.successful(())
            }

        val manager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomPublicKey)),
                managerPersistenceId)
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded]
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig]
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance]
        Stream.continually({
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport]
          instanceStatus.state
        }).filter(_ == Instance.Running).head
        // Now terminate the instance
        probe.send(manager, TerminateInstance(response.instanceId))
        probe.expectMsgType[RktClusterManager.TerminatingInstance.type]
        // Poll 10 times, 100ms apart to check if we've terminated
        Stream.fill(10)({
          Thread.sleep(100)
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport]
          instanceStatus.state
        }).filter(_ == Instance.Finished).headOption must beSome
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
      override def fetch(imageName: String): Future[String] = ???
      override def start(
          instanceId: String,
          image: String,
          callbackUrl: String): Future[RSAPublicKey] = ???
      override def stop(instanceId: String): Future[Unit] = ???
    }

  def mockFetchSshHostKey(
      pk: RSAPublicKey)(host: String, port: Int): Future[RSAPublicKey] =
        Future.successful(pk)

}
