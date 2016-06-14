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

  "ClusterRoutes" >> {

    "get cluster info" >> {

      // We never want an empty string for these checks
      implicit val arbString = Arbitrary(genNonEmptyString)

      "exists" >> prop { id: String =>
        val clusterRoutes = (new ClusterRoutes(TestActorRef(
            fixedResponseActor(ClusterAggregate.RktCluster(id))))).routes
        Get(basePath / id) ~> clusterRoutes ~> check {
          (status must beSuccess) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> id)
            /("type" -> ClusterAggregate.ClusterTypes.Rkt.toString)
          })
        }
      }

      "does not exist" >> prop { id: String =>
        val clusterRoutes = (new ClusterRoutes(TestActorRef(
            fixedResponseActor(ClusterAggregate.Uninitialized)))).routes
        Get(basePath / id) ~> clusterRoutes ~> check {
          status must be_==(StatusCodes.NotFound)
        }
      }

    }

    "add rkt node" >> prop({
      (clusterId: String, host: String, port: Int, username: String)  =>
        val postJson = Json.obj(
            "host" -> host,
            "port" -> port,
            "username" -> username)
        val ckp = RktNode.ClientKeyPair(
            randomPublicKey, fakePrivateKey)
        val spk = RktNode.ServerPublicKey(randomPublicKey)
        val response = RktNode.NodeConfig(
          RktNode.newId,
          RktNode.ServerConnectionDetails(host, port, username, ckp, spk),
          "/var/lib/dit4c-rkt",
          false)
        val routes = (new ClusterRoutes(TestActorRef(
            fixedResponseActor(response)))).routes
        Post(basePath / clusterId / "nodes", postJson) ~> routes ~> check {
          (status must be(StatusCodes.Created)) and
          (Json.prettyPrint(entityAs[JsValue]) must {
            /("id" -> response.id) and
            /("host" -> response.connectionDetails.host) and
            /("port" -> response.connectionDetails.port) and
            /("username" -> response.connectionDetails.username) and
            /("client-key") /("kty" -> "RSA") and
            /("client-key") /("e" -> toBase64url(ckp.public.getPublicExponent)) and
            /("client-key") /("n" -> toBase64url(ckp.public.getModulus)) and
            /("host-key") /("kty" -> "RSA") and
            /("host-key") /("e" -> toBase64url(spk.public.getPublicExponent)) and
            /("host-key") /("n" -> toBase64url(spk.public.getModulus))
          })
        }
    }).noShrink // Most likely shrinking won't help narrow down errors
      .setGens(
        genAggregateId,
        Gen.identifier, // Could be wider, but this will do for now
        Gen.choose(1, 0xFFFF), // Valid TCP port
        Gen.identifier) // Could be wider, but this will do for now
  }

  def fixedResponseActor[T](response: T) =
    new Actor {
      def receive = {
        case _ => sender ! response
      }
    }

  def toBase64url(bi: java.math.BigInteger): String =
    JwtBase64.encodeString(bi.toByteArray)

  def randomPublicKey: RSAPublicKey =
    new RSAPublicKey() {
      override val getModulus = BigInt(512, Random).bigInteger
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