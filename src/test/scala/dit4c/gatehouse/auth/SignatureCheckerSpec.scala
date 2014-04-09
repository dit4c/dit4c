package dit4c.gatehouse.auth

import org.specs2.mutable.Specification
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.crypto.RSASSASigner
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey

class SignatureCheckerSpec extends Specification {

  lazy val testKeys: Seq[RSAKey] = KeyLoader(
        getClass.getResourceAsStream("test_jwk.json"))
  lazy val checker = new SignatureChecker(testKeys.map(_.toRSAPublicKey).toSet)

  "Signature Checker" should {

    "should reject things that aren't tokens" in {
      import com.nimbusds.jose._
      val unsignedToken = "VGhlIHF1aWNrIGJyb3duIGZveA.anVtcGVkIG92ZXI"
      checker(unsignedToken) must beLeft
    }

    "should reject unsigned tokens" in {
      import com.nimbusds.jose._
      val unsignedToken = new PlainObject(new Payload("Test payload")).serialize
      checker(unsignedToken) must beLeft
    }

    "should accept tokens signed with the test key" in {
      import com.nimbusds.jose._
      val header = new JWSHeader(JWSAlgorithm.RS256)
      val payload = new Payload("Test payload")
      testKeys.map { key =>
        val signer = new RSASSASigner(key.toRSAPrivateKey)
        val token = new JWSObject(header, payload)
        token.sign(signer)
        checker(token.serialize) must beRight
      }
    }

    "should reject tokens signed with another key" in {
      import com.nimbusds.jose._
      val key = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512) // Yes, it's weak, but it's fast!
        generator.genKeyPair().getPrivate().asInstanceOf[RSAPrivateKey]
      }
      val header = new JWSHeader(JWSAlgorithm.RS256)
      val payload = new Payload("Test payload")
      val signer = new RSASSASigner(key)
      val signature = new RSASSASigner(key).sign(header, payload.toBytes)
      val token = new JWSObject(header, payload)
      token.sign(signer)
      checker(token.serialize) must beLeft
    }
  }
}

