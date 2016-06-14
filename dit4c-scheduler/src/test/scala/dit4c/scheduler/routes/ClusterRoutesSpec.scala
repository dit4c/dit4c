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
import org.scalacheck.ArbitraryLowPriority
import dit4c.scheduler.ScalaCheckHelpers
import dit4c.scheduler.domain.RktNode
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import scala.util.Random
import pdi.jwt.JwtBase64

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
          import ClusterAggregate.RktCluster
          def receive = {
            case GetCluster(`id`) => sender ! RktCluster(id)
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
      (clusterId: String, response: RktNode.NodeConfig) =>
        val path = basePath / clusterId / "nodes"
        val clientPubKey = response.connectionDetails.clientKey.public
        val serverPubKey = response.connectionDetails.serverKey.public
        val postJson = Json.obj(
            "host" -> response.connectionDetails.host,
            "port" -> response.connectionDetails.port,
            "username" -> response.connectionDetails.username)
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import ClusterAggregate.AddRktNode
          def receive = {
            case ClusterCommand(`clusterId`, _: AddRktNode) =>
              sender ! response
          }
        }
        Post(path, postJson) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.Created)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> response.id) and
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
      .setGens(genAggregateId, genNodeConfig(false))

    "get rkt node" >> prop({
      (clusterId: String, response: RktNode.NodeConfig) =>
        val path = basePath / clusterId / "nodes" / response.id
        val clientPubKey = response.connectionDetails.clientKey.public
        val serverPubKey = response.connectionDetails.serverKey.public
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import ClusterAggregate.GetRktNodeState
          def receive = {
            case ClusterCommand(`clusterId`, GetRktNodeState(response.id)) =>
              sender ! response
          }
        }
        Get(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> response.id) and
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
      .setGens(genAggregateId, genNodeConfig(false))

    "confirm keys for rkt node" >> prop({
      (clusterId: String, response: RktNode.NodeConfig)  =>
        val path = basePath / clusterId / "nodes" / response.id / "confirm-keys"
        def testActor = new Actor {
          import ClusterAggregateManager.ClusterCommand
          import ClusterAggregate.ConfirmRktNodeKeys
          def receive = {
            case ClusterCommand(`clusterId`, ConfirmRktNodeKeys(response.id)) =>
              sender ! response
          }
        }
        Put(path) ~> routes(testActor) ~> check {
          (status must be(StatusCodes.OK))
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(genAggregateId, genNodeConfig(true))

  }

  def fixedResponseActor[T](response: T) =
    new Actor {
      def receive = {
        case _ => sender ! response
      }
    }

  private implicit val arbNodeConfig = Arbitrary(genNodeConfig(false))

  private def genNodeConfig(confirmed: Boolean): Gen[RktNode.NodeConfig] =
    for {
      rktNodeId <- Gen.resultOf((_: Int) => RktNode.newId)
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
      RktNode.newId,
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