package dit4c.scheduler.routes

import dit4c.scheduler.Specs2RouteTest
import org.specs2.matcher.JsonMatchers
import org.specs2.execute.Result
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import org.specs2.matcher.JsonType
import dit4c.scheduler.service.ClusterAggregateManager
import org.specs2.ScalaCheck
import akka.http.scaladsl.model.StatusCodes
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalacheck.Arbitrary
import akka.http.scaladsl.model.Uri
import org.scalacheck.Gen
import org.specs2.scalacheck.Parameters
import akka.testkit.TestActorRef
import akka.actor.Actor
import dit4c.scheduler.domain.ClusterAggregate
import dit4c.scheduler.domain.RktClusterManager
import dit4c.scheduler.domain.Instance
import org.scalacheck.ArbitraryLowPriority
import dit4c.scheduler.ScalaCheckHelpers
import dit4c.scheduler.domain.RktNode
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import scala.util.Random
import pdi.jwt.JwtBase64
import akka.http.scaladsl.model.headers.Location
import dit4c.common.KeyHelpers._
import org.bouncycastle.openpgp.PGPPublicKey

class ClusterRoutesSpec extends Specs2RouteTest
    with JsonMatchers with PlayJsonSupport
    with ScalaCheck with ScalaCheckHelpers {
  import dit4c.scheduler.domain.clusteraggregate.ClusterType

  val basePath: Uri.Path = Uri.Path / "clusters"
  implicit def path2uri(path: Uri.Path) = Uri(path=path)

  def routes(testActor: => Actor) =
    (new ClusterRoutes(TestActorRef(testActor))).routes

  "ClusterRoutes" >> {

    "get cluster info" >> {

      // We never want an empty string for these checks
      implicit val arbString = Arbitrary(genNonEmptyString)

      "exists" >> prop { id: String =>
        def testActor = new Actor {
          import ClusterAggregateManager.GetCluster
          import ClusterAggregate.ClusterOfType
          def receive = {
            case GetCluster(`id`) => sender ! ClusterOfType(ClusterType.Rkt)
          }
        }
        Get(basePath / id) ~> routes(testActor) ~> check {
          (status must beSuccess) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> id)
            /("type" -> ClusterType.Rkt.toString)
          })
        }
      }

      "does not exist" >> prop { id: String =>
        def testActor = new Actor {
          import ClusterAggregateManager.GetCluster
          import ClusterAggregate.UninitializedCluster
          def receive = {
            case GetCluster(`id`) => sender ! UninitializedCluster
          }
        }
        Get(basePath / id) ~> routes(testActor) ~> check {
          status must be_==(StatusCodes.NotFound)
        }
      }

    }

    "add rkt node" >> prop({
      (clusterId: String, nodeId: String, nodeConfig: RktNode.NodeConfig) =>
        val path = basePath / clusterId / "nodes"
        val clientPubKey = nodeConfig.connectionDetails.clientKey.public
        val serverPubKey = nodeConfig.connectionDetails.serverKey.public
        val postJson = Json.obj(
            "host" -> nodeConfig.connectionDetails.host,
            "port" -> nodeConfig.connectionDetails.port,
            "username" -> nodeConfig.connectionDetails.username)
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.{AddRktNode, RktNodeAdded, GetRktNodeState}
          import RktNode.Exists
          def receive = {
            case ClusterCommand(`clusterId`, _: AddRktNode) =>
              sender ! RktNodeAdded(nodeId)
            case ClusterCommand(`clusterId`, GetRktNodeState(`nodeId`)) =>
              sender ! Exists(nodeConfig)
            case cmd => println(cmd)
          }
        }
        Post(path, postJson) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.Created)) and
          (header("Location") must beSome(Location(path / nodeId))) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("host" -> nodeConfig.connectionDetails.host) and
            /("port" -> nodeConfig.connectionDetails.port) and
            /("username" -> nodeConfig.connectionDetails.username) and
            /("client-key") /("jwk") /("kty" -> "RSA") and
            /("client-key") /("jwk") /("e" -> toBase64url(clientPubKey.getPublicExponent)) and
            /("client-key") /("jwk") /("n" -> toBase64url(clientPubKey.getModulus)) and
            /("client-key") /("ssh") /("fingerprints") /(clientPubKey.ssh.fingerprint("MD5")) and
            /("client-key") /("ssh") /("fingerprints") /(clientPubKey.ssh.fingerprint("SHA-256")) and
            /("client-key") /("ssh") /("openssh" -> clientPubKey.ssh.authorizedKeys) and
            /("client-key") /("ssh") /("ssh2" -> clientPubKey.ssh.pem) and
            /("host-key") /("jwk") /("kty" -> "RSA") and
            /("host-key") /("jwk") /("e" -> toBase64url(serverPubKey.getPublicExponent)) and
            /("host-key") /("jwk") /("n" -> toBase64url(serverPubKey.getModulus)) and
            /("host-key") /("ssh") /("fingerprints") /(serverPubKey.ssh.fingerprint("MD5")) and
            /("host-key") /("ssh") /("fingerprints") /(serverPubKey.ssh.fingerprint("SHA-256")) and
            /("host-key") /("ssh") /("openssh" -> serverPubKey.ssh.authorizedKeys) and
            /("host-key") /("ssh") /("ssh2" -> serverPubKey.ssh.pem)
          })
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(genAggregateId, Gen.identifier, genNodeConfig(false))

    "get rkt node" >> prop({
      (clusterId: String, nodeId: String, response: RktNode.NodeConfig) =>
        val path = basePath / clusterId / "nodes" / nodeId
        val clientPubKey = response.connectionDetails.clientKey.public
        val serverPubKey = response.connectionDetails.serverKey.public
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.GetRktNodeState
          def receive = {
            case ClusterCommand(`clusterId`, GetRktNodeState(nodeId)) =>
              sender ! RktNode.Exists(response)
          }
        }
        Get(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("host" -> response.connectionDetails.host) and
            /("port" -> response.connectionDetails.port) and
            /("username" -> response.connectionDetails.username) and
            /("client-key") /("jwk") /("kty" -> "RSA") and
            /("client-key") /("jwk") /("e" -> toBase64url(clientPubKey.getPublicExponent)) and
            /("client-key") /("jwk") /("n" -> toBase64url(clientPubKey.getModulus)) and
            /("client-key") /("ssh") /("fingerprints") /(clientPubKey.ssh.fingerprint("MD5")) and
            /("client-key") /("ssh") /("fingerprints") /(clientPubKey.ssh.fingerprint("SHA-256")) and
            /("client-key") /("ssh") /("openssh" -> clientPubKey.ssh.authorizedKeys) and
            /("client-key") /("ssh") /("ssh2" -> clientPubKey.ssh.pem) and
            /("host-key") /("jwk") /("kty" -> "RSA") and
            /("host-key") /("jwk") /("e" -> toBase64url(serverPubKey.getPublicExponent)) and
            /("host-key") /("jwk") /("n" -> toBase64url(serverPubKey.getModulus)) and
            /("host-key") /("ssh") /("fingerprints") /(serverPubKey.ssh.fingerprint("MD5")) and
            /("host-key") /("ssh") /("fingerprints") /(serverPubKey.ssh.fingerprint("SHA-256")) and
            /("host-key") /("ssh") /("openssh" -> serverPubKey.ssh.authorizedKeys) and
            /("host-key") /("ssh") /("ssh2" -> serverPubKey.ssh.pem)
          })
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(genAggregateId, Gen.identifier, genNodeConfig(false))

    "confirm keys for rkt node" >> prop({
      (clusterId: String, nodeId: String, response: RktNode.NodeConfig)  =>
        val path = basePath / clusterId / "nodes" / nodeId / "confirm-keys"
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.ConfirmRktNodeKeys
          def receive = {
            case ClusterCommand(`clusterId`, ConfirmRktNodeKeys(nodeId)) =>
              sender ! RktNode.Exists(response)
          }
        }
        Put(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK))
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(genAggregateId, Gen.identifier, genNodeConfig(true))

   "get instance status" >> prop({
      (clusterId: String, instanceId: String, imageName: String, portalUri: Uri, signingKey: PGPPublicKey) =>
        val path = basePath / clusterId / "instances" / instanceId
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.GetInstanceStatus
          import Instance.{StatusReport, WaitingForImage, StartData}
          def receive = {
            case ClusterCommand(`clusterId`, GetInstanceStatus(`instanceId`)) =>
              sender ! StatusReport(
                  Instance.WaitingForImage,
                  StartData(
                      instanceId,
                      imageName,
                      None,
                      portalUri.toString,
                      Some(Instance.SigningKey(signingKey))))
          }
        }
        Get(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("state" -> Instance.WaitingForImage.identifier) and
            /("image") /("name" -> imageName) and
            /("portal" -> portalUri.toString) and
            /("key") /("jwk") /("kid" -> instanceId) and
            /("key") /("jwk") /("kty" -> "RSA")
          })
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGen1(genAggregateId)
      .setGen2(Gen.identifier)
      .setGen3(Gen.identifier)

  }

  private implicit val arbRSAPublicKey = Arbitrary(Gen.resultOf(randomPublicKey _))

  private implicit val arbPGPPublicKey = Arbitrary(Gen.identifier.map(PGPKeyGenerators.RSA(_, 1024).getPublicKey))

  private implicit val arbNodeConfig = Arbitrary(genNodeConfig(false))

  private def genNodeConfig(confirmed: Boolean): Gen[RktNode.NodeConfig] =
    for {
      host <- Gen.identifier // Could be wider, but this will do for now
      port <- Gen.choose(1, 0xFFFF) // Valid TCP port
      username <- Gen.identifier // Could be wider, but this will do for now
      ckp <- Gen.resultOf(randomPublicKey _).map { publicKey =>
        RktNode.ClientKeyPair(publicKey, fakePrivateKey)
      }
      spk <- Gen.resultOf(randomPublicKey _).map { publicKey =>
        RktNode.ServerPublicKey(publicKey)
      }
    } yield RktNode.NodeConfig(
      RktNode.ServerConnectionDetails(host, port, username, ckp, spk),
      "/var/lib/dit4c-rkt",
      false)

  def toBase64url(bi: java.math.BigInteger): String =
    JwtBase64.encodeString(bi.toByteArray)

  def randomPublicKey(modulus: BigInt): RSAPublicKey =
    new RSAPublicKey() {
      override val getModulus = modulus.bigInteger
      override val getPublicExponent = BigInt(65537).bigInteger
      def getAlgorithm(): String = ???
      def getEncoded(): Array[Byte] = ???
      def getFormat(): String = ???
    }

  // Used to ensure private key is never accessed
  val fakePrivateKey = new RSAPrivateKey() {
    // Members declared in java.security.Key
    def getAlgorithm(): String = ???
    def getEncoded(): Array[Byte] = ???
    def getFormat(): String = ???

    // Members declared in java.security.interfaces.RSAKey
    def getModulus(): java.math.BigInteger = ???

    // Members declared in java.security.interfaces.RSAPrivateKey
    def getPrivateExponent(): java.math.BigInteger = ???
  }

}
