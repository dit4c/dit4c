package dit4c.machineshop.auth

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.KeyFactory
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.Signature
import java.util.concurrent.TimeUnit
import org.specs2.mutable.Specification
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.Use
import spray.httpx.RequestBuilding._
import akka.util.Timeout
import spray.http.HttpRequest
import spray.http.HttpHeaders
import spray.http.GenericHttpCredentials
import spray.http.DateTime
import com.nimbusds.jose.util.Base64
import com.nimbusds.jose.util.Base64URL

class SignatureVerifierSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  "SignatureVerifier" >> {

    "verifies a HTTP signature" >> {
      import dit4c.HttpSignatureUtils._

      val keyId = "Test RSA key (512 bit)"
      val (keySet, privateKey) = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512) // Yes, it's weak, but it's fast!
        val kp = generator.generateKeyPair
        val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
        val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
        val k = new RSAKey(pub, priv, Use.SIGNATURE, JWSAlgorithm.parse("RSA"),
            keyId, null, null, null)
        (new JWKSet(k), priv)
      }

      val verifier = new SignatureVerifier(keySet.toPublicJWKSet)

      Seq(`RSA-SHA1`, `RSA-SHA256`).foreach { algorithm =>
        val request = Post("/create?name=foo", "") ~>
          addHeader(HttpHeaders.Date(DateTime.now)) ~>
          addHeader(HttpHeaders.`Content-Length`(0)) ~>
          addSignature(
              keyId, privateKey, algorithm,
              Seq("(request-target)", "date", "content-length"))

        // Check valid signature verifies
        verifier(request) must beRight
        // Check validation fails if we change the date
        verifier(
          request
            ~> removeHeader("Date")
            ~> addHeader(HttpHeaders.Date(DateTime(0)))) must beLeft[String]
      }

      done
    }
  }
}


