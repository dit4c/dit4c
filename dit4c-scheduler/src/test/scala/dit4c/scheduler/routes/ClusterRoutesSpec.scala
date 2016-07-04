package dit4c.scheduler.routes

import dit4c.scheduler.Specs2RouteTest
import org.specs2.matcher.JsonMatchers
import org.specs2.execute.Result
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import org.specs2.matcher.JsonType
import dit4c.scheduler.service.ClusterAggregateManager
import akka.actor.Props
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

class ClusterRoutesSpec extends Specs2RouteTest
    with JsonMatchers with PlayJsonSupport
    with ScalaCheck with ScalaCheckHelpers {

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
          import ClusterAggregate.ClusterTypes.Rkt
          def receive = {
            case GetCluster(`id`) => sender ! Rkt
          }
        }
        Get(basePath / id) ~> routes(testActor) ~> check {
          (status must beSuccess) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> id)
            /("type" -> ClusterAggregate.ClusterTypes.Rkt.toString)
          })
        }
      }

      "does not exist" >> prop { id: String =>
        def testActor = new Actor {
          import ClusterAggregateManager.GetCluster
          import ClusterAggregate.Uninitialized
          def receive = {
            case GetCluster(`id`) => sender ! Uninitialized
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
          def receive = {
            case ClusterCommand(`clusterId`, _: AddRktNode) =>
              sender ! RktNodeAdded(nodeId)
            case ClusterCommand(`clusterId`, GetRktNodeState(`nodeId`)) =>
              sender ! nodeConfig
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
            /("client-key") /("kty" -> "RSA") and
            /("client-key") /("e" -> toBase64url(clientPubKey.getPublicExponent)) and
            /("client-key") /("n" -> toBase64url(clientPubKey.getModulus)) and
            /("host-key") /("kty" -> "RSA") and
            /("host-key") /("e" -> toBase64url(serverPubKey.getPublicExponent)) and
            /("host-key") /("n" -> toBase64url(serverPubKey.getModulus))
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
              sender ! response
          }
        }
        Get(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("host" -> response.connectionDetails.host) and
            /("port" -> response.connectionDetails.port) and
            /("username" -> response.connectionDetails.username) and
            /("client-key") /("kty" -> "RSA") and
            /("client-key") /("e" -> toBase64url(clientPubKey.getPublicExponent)) and
            /("client-key") /("n" -> toBase64url(clientPubKey.getModulus)) and
            /("host-key") /("kty" -> "RSA") and
            /("host-key") /("e" -> toBase64url(serverPubKey.getPublicExponent)) and
            /("host-key") /("n" -> toBase64url(serverPubKey.getModulus))
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
              sender ! response
          }
        }
        Put(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK))
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(genAggregateId, Gen.identifier, genNodeConfig(true))

    "start instance" >> prop({
      (clusterId: String, imageName: String, callbackUrl: Uri, responseId: String) =>
        val path = basePath / clusterId / "instances"
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.{StartInstance, StartingInstance}
          import Instance.NamedImage
          val callbackAsString = callbackUrl.toString
          def receive = {
            case ClusterCommand(`clusterId`,
                StartInstance(NamedImage(`imageName`), `callbackAsString`)) =>
              sender ! StartingInstance(responseId)
          }
        }
        val postJson = Json.obj(
            "image" -> imageName,
            "callback" -> callbackUrl.toString)
        Post(path, postJson) ~> routes(testActor) ~> check {
          status must be(StatusCodes.Accepted)
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGen1(genAggregateId)
      .setGen2(Gen.identifier)
      .setGen4(Gen.identifier)

    "unable to start instance" >> prop({
      (clusterId: String, imageName: String, callbackUrl: Uri) =>
        val path = basePath / clusterId / "instances"
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.{StartInstance, UnableToStartInstance}
          import Instance.NamedImage
          val callbackAsString = callbackUrl.toString
          def receive = {
            case ClusterCommand(`clusterId`,
                StartInstance(NamedImage(`imageName`), `callbackAsString`)) =>
              sender ! UnableToStartInstance
          }
        }
        val postJson = Json.obj(
            "image" -> imageName,
            "callback" -> callbackUrl.toString)
        Post(path, postJson) ~> routes(testActor) ~> check {
          status must be(StatusCodes.ServiceUnavailable)
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGen1(genAggregateId)
      .setGen2(Gen.identifier)

   "get instance status" >> prop({
      (clusterId: String, instanceId: String, imageName: String, callbackUrl: Uri, signingKey: RSAPublicKey) =>
        val path = basePath / clusterId / "instances" / instanceId
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.GetInstanceStatus
          import Instance.{StatusReport, WaitingForImage, StartData, NamedImage}
          def receive = {
            case ClusterCommand(`clusterId`, GetInstanceStatus(`instanceId`)) =>
              sender ! StatusReport(
                  Instance.WaitingForImage,
                  StartData(
                      instanceId,
                      NamedImage(imageName),
                      None,
                      callbackUrl.toString,
                      Some(Instance.RSAPublicKey(signingKey))))
          }
        }
        Get(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("state" -> Instance.WaitingForImage.identifier) and
            /("image") /("name" -> imageName) and
            /("callback" -> callbackUrl.toString) and
            /("key") /("kty" -> "RSA")
          })
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGen1(genAggregateId)
      .setGen2(Gen.identifier)
      .setGen3(Gen.identifier)

   "terminate instance" >> prop({
      (clusterId: String, instanceId: String) =>
        val path = basePath / clusterId / "instances" / instanceId / "terminate"
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import RktClusterManager.{TerminateInstance, TerminatingInstance}
          def receive = {
            case ClusterCommand(`clusterId`, TerminateInstance(`instanceId`)) =>
              sender ! TerminatingInstance
          }
        }
        Put(path) ~> routes(testActor) ~> check {
          status must be(StatusCodes.Accepted)
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGen1(genAggregateId)
      .setGen2(Gen.identifier)

  }

  private implicit val arbPublicKey = Arbitrary(Gen.resultOf(randomPublicKey _))

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