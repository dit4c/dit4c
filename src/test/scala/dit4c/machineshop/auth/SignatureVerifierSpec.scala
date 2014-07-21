package dit4c.machineshop.auth

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

import org.specs2.mutable.Specification

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.Use

import akka.util.Timeout

class SignatureVerifierSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  "SignatureVerifier" >> {

    "verifies a JWT " >> {
      val keySet = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512) // Yes, it's weak, but it's fast!
        val kp = generator.generateKeyPair
        val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
        val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
        val k = new RSAKey(pub, priv, Use.SIGNATURE, JWSAlgorithm.parse("RSA"),
            "Test RSA key (512 bit)", null, null, null)
        new JWKSet(k)
      }

      new SignatureVerifier(keySet.toPublicJWKSet)

      pending
    }
  }
}


