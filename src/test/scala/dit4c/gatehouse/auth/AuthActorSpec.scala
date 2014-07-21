package dit4c.gatehouse.auth

import org.specs2.mutable.Specification
import akka.testkit.TestActorRef
import java.io.File
import akka.actor.Props
import akka.actor.ActorSystem
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPublicKey, RSAPrivateKey}
import com.nimbusds.jose._
import com.nimbusds.jose.jwk._
import java.io.{BufferedWriter, FileWriter}
import dit4c.gatehouse.auth.AuthActor.AuthCheck
import akka.pattern.ask
import akka.util.Timeout
import org.specs2.time.NoTimeConversions

class AuthActorSpec extends Specification with NoTimeConversions {
  import scala.concurrent.duration._
  import AuthActor._
  implicit val system = ActorSystem("testSystem")
  implicit val timeout = Timeout(1.second)

  "Auth Actor" should {

    "should not die if fed an empty file" in {
      val keyFile = File.createTempFile("dit4c-", "-testkeyfile")
      keyFile.deleteOnExit()

      val actorRef = TestActorRef[AuthActor](
          Props(classOf[AuthActor], new java.net.URI(keyFile.getAbsolutePath)))

      actorRef.underlyingActor must not beNull
    }

    "should grant access to valid tokens and deny to invalid tokens" in {

      val keyFile = File.createTempFile("dit4c-", "-testkeyfile")
      keyFile.deleteOnExit()

      val privateKey = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512) // Yes, it's weak, but it's fast!
        val kp = generator.generateKeyPair
        val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
        val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
        val k = new RSAKey(pub, priv, Use.SIGNATURE, JWSAlgorithm.parse("RSA"),
            "Test RSA key (512 bit)", null, null, null)
        val publicKeySet = new JWKSet(k).toPublicJWKSet();
        {
          val w = new BufferedWriter(new FileWriter(keyFile))
          w.write(publicKeySet.toString)
          w.close
        }
        priv
      }

      val token = {
        import com.nimbusds.jose.crypto.RSASSASigner
        val tokenString = {
          val now = System.currentTimeMillis
          s"""{
            "iss":"http://dit4c.github.io/",
            "iat": $now,
            "http://dit4c.github.io/authorized_containers": ["foo", "bar"]
          }"""
        }
        val header = new JWSHeader(JWSAlgorithm.RS256)
        val payload = new Payload(tokenString)
        val signer = new RSASSASigner(privateKey)
        val token = new JWSObject(header, payload)
        token.sign(signer)
        token.serialize
      }

      val actorRef = TestActorRef[AuthActor](
          Props(classOf[AuthActor], new java.net.URI(keyFile.getAbsolutePath)))

      (actorRef ? AuthCheck(token, "foo")) must be_==(AccessGranted).await
      (actorRef ? AuthCheck(token, "invalid")) must beAnInstanceOf[AccessDenied].await
    }



  }

}