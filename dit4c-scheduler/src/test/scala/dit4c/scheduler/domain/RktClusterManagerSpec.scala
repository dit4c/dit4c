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
import java.nio.file.Paths
import org.bouncycastle.openpgp.PGPPublicKey
import dit4c.common.KeyHelpers.PGPKeyGenerators

class RktClusterManagerSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import RktClusterManager._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))
  implicit val rktRunnerConfig =
    RktRunner.Config(
        Paths.get("/var/lib/dit4c-rkt"),
        "dit4c-instance-",
          "" /* Not used */,
          "" /* Not used */)

  "ClusterAggregate" >> {

    "GetRktNodeState" >> {

      "initially returns Uninitialized" >> {
        implicit val system =
          ActorSystem("RktClusterManager-GetRktNodeState-Uninitialized")
        prop({ (managerPersistenceId: String, rktNodeId: String) =>
          val manager =
              system.actorOf(RktClusterManager.props(rktRunnerConfig), managerPersistenceId)
          val probe = TestProbe()
          probe.send(manager, GetRktNodeState(rktNodeId))
          probe.expectMsgType[RktNode.Data](1.minute) must {
            be(RktNode.NoConfig)
          }
        }).setGens(Gen.identifier, Gen.listOfN(8, Gen.numChar).map(_.mkString))
      }

    }

    "AddRktNode" >> {
      "initializes RktNode with config" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system = ActorSystem("RktClusterManager-AddRktNode")
        val hostPublicKey = randomRSAPublicKey
        val manager =
            system.actorOf(
                RktClusterManager.props(mockRktRunnerFactory,
                    mockFetchSshHostKey(hostPublicKey)),
                managerPersistenceId)
        val probe = TestProbe()
        probe.send(manager, AddRktNode(
            "169.254.42.34", 22, "testuser", "/var/lib/dit4c/rkt"))
        val response = probe.expectMsgType[RktNodeAdded](1.minute)
        probe.send(manager, ClusterManager.GetStatus)
        val clusterState = probe.expectMsgType[ClusterInfo](1.minute)
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
                    mockFetchSshHostKey(randomRSAPublicKey)),
                managerPersistenceId)
        val probe = TestProbe()
        probe.send(manager, AddRktNode(
            "169.254.42.64", 22, "testuser", "/var/lib/dit4c/rkt"))
        val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
        probe.send(manager, ConfirmRktNodeKeys(nodeId))
        val updatedConfig = probe.expectMsgType[RktNode.NodeConfig](1.minute)
        ( updatedConfig.readyToConnect must beTrue )
      }
    }

    "StartInstance" >> {
      "starts an instance" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-StartInstance-start")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  helperImage: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
            }

        val manager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomRSAPublicKey)),
                managerPersistenceId)
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig](1.minute)
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport](1.minute)
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
        val resolvedPublicKey = randomPGPPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] = ???
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  helperImage: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
            }

        def createManager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomRSAPublicKey)),
                managerPersistenceId)
        val manager = createManager
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig](1.minute)
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        (response must {
          import scala.language.experimental.macros
          matchA[RktClusterManager.StartingInstance]
            .instanceId(not(beEmpty[String]))
        }) and
        {
          probe.watch(manager)
          probe.send(manager, Shutdown)
          probe.expectMsgType[Terminated](1.minute)
          val newManager = createManager
          probe.send(newManager, GetInstanceStatus(response.instanceId))
          val instanceStatus =
            probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.data must beLike {
            case Instance.StartData(id, providedImage, _, callback, key) =>
              ( id must be_==(response.instanceId) ) and
              ( providedImage must be_==(testImage) ) and
              ( callback must be_==(testCallback) )
          }
        }
      }
    }

    "SaveInstance" >> {
      "saves an instance" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-SaveInstance")
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] =
                Future.successful(())
              override def export(instanceId: String) = Future.successful(())
              override def uploadImage(instanceId: String,
                  helperImage: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = Future.successful(())
            }

        val manager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomRSAPublicKey)),
                managerPersistenceId)
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig](1.minute)
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        Stream.continually({
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Running).head
        // Now save the instance
        val testSaveHelperImage = Instance.NamedImage("docker://busybox")
        probe.send(manager, InstanceEnvelope(response.instanceId, Instance.Save(testSaveHelperImage, "")))
        probe.expectMsgType[Instance.Ack.type](1.minute)
        // Poll 10 times, 100ms apart to check if we've discarded the instance
        Stream.fill(10)({
          Thread.sleep(100)
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Uploaded).headOption must beSome
      }
    }

    "DiscardInstance" >> {
      "discards an instance" >> {
        val managerPersistenceId = "Cluster-test-rkt"
        implicit val system =
          ActorSystem(s"RktClusterManager-DiscardInstance")
        val log = system.log
        val resolvedImageId = "sha512-"+Stream.fill(64)("0").mkString
        val resolvedPublicKey = randomPGPPublicKey
        val runnerFactory =
          (_: RktNode.ServerConnectionDetails, _: String) =>
            new RktRunner {
              override def fetch(imageName: String): Future[String] =
                Future.successful(resolvedImageId)
              override def start(
                  instanceId: String,
                  image: String,
                  portalUri: String): Future[PGPPublicKey] =
                Future.successful(resolvedPublicKey)
              override def stop(instanceId: String): Future[Unit] =
                Future.successful(())
              override def export(instanceId: String) = ???
              override def uploadImage(instanceId: String,
                  helperImage: String,
                  imageServer: String,
                  portalUri: String): Future[Unit] = ???
            }

        val manager =
            system.actorOf(
                RktClusterManager.props(runnerFactory,
                    mockFetchSshHostKey(randomRSAPublicKey)),
                managerPersistenceId)
        // Create some nodes
        val nodeIds = 1.to(3).map { i =>
          val probe = TestProbe()
          probe.send(manager, AddRktNode(
              s"169.254.42.$i", 22, "testuser", "/var/lib/dit4c/rkt"))
          val RktNodeAdded(nodeId) = probe.expectMsgType[RktNodeAdded](1.minute)
          probe.send(manager, ConfirmRktNodeKeys(nodeId))
          probe.expectMsgType[RktNode.NodeConfig](1.minute)
          nodeId
        }
        // Schedule an instance
        val probe = TestProbe()
        val testImage = NamedImage("docker://dit4c/gotty:latest")
        val testCallback = "http://example.test/"
        probe.send(manager, StartInstance(randomInstanceId, testImage, testCallback))
        val response = probe.expectMsgType[RktClusterManager.StartingInstance](1.minute)
        Stream.continually({
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Running).head
        // Now discard the instance
        probe.send(manager, InstanceEnvelope(response.instanceId, Instance.Discard))
        probe.expectMsgType[Instance.Ack.type](1.minute)
        // Poll 10 times, 100ms apart to check if we've discarded the instance
        Stream.fill(10)({
          Thread.sleep(100)
          probe.send(manager, GetInstanceStatus(response.instanceId))
          val instanceStatus = probe.expectMsgType[Instance.StatusReport](1.minute)
          instanceStatus.state
        }).filter(_ == Instance.Discarded).headOption must beSome
      }
    }

  }

  def randomRSAPublicKey: RSAPublicKey = {
    val sr = SecureRandom.getInstance("SHA1PRNG")
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512, sr)
    kpg.genKeyPair.getPublic.asInstanceOf[RSAPublicKey]
  }

  def randomPGPPublicKey: PGPPublicKey = PGPKeyGenerators.RSA(Random.alphanumeric.take(20).mkString).getPublicKey

  def mockRktRunnerFactory(
      cd: RktNode.ServerConnectionDetails, dir: String): RktRunner =
    new RktRunner {
      override def fetch(imageName: String): Future[String] = ???
      override def start(
          instanceId: String,
          image: String,
          portalUri: String): Future[PGPPublicKey] = ???
      override def stop(instanceId: String): Future[Unit] = ???
      override def export(instanceId: String) = ???
      def uploadImage(instanceId: String,
          helperImage: String,
          imageServer: String,
          portalUri: String): Future[Unit] = ???
    }

  def mockFetchSshHostKey(
      pk: RSAPublicKey)(host: String, port: Int): Future[RSAPublicKey] =
        Future.successful(pk)

  def randomInstanceId = Random.alphanumeric.take(20).mkString

}
