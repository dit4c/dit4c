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

    "verifies a JWT " >> {
      val keyId = "Test RSA key (512 bit)"
      val keySet = {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(512) // Yes, it's weak, but it's fast!
        val kp = generator.generateKeyPair
        val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
        val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
        val k = new RSAKey(pub, priv, Use.SIGNATURE, JWSAlgorithm.parse("RSA"),
            keyId, null, null, null)
        new JWKSet(k)
      }

      def addSignature(requiredHeaders: Seq[String]) =
        { req: HttpRequest =>
          def headerValue(name: String) =
            if (name == "(request-target)")
              s"${req.method.value.toLowerCase} ${req.uri.path}"
            else
              req.headers
                .find(h => h.name.toLowerCase == name) // First header with name
                .map(_.value).getOrElse("") // Convert to string

          val signingString = requiredHeaders
            .map { hn => s"$hn: ${headerValue(hn)}" }
            .mkString("\n")

          val algorithm = "rsa-sha256"

          def signature(signingString: String): Base64 = {
            def getSignatureInstance(alg: String): Signature =
              Signature.getInstance(alg match {
                case "rsa-sha1"   => "SHA1withRSA"
                case "rsa-sha256" => "SHA256withRSA"
              })

            val privateKey =
              keySet.getKeyByKeyId(keyId).asInstanceOf[RSAKey].toRSAPrivateKey

            val sig = getSignatureInstance(algorithm)
            sig.initSign(privateKey)
            sig.update(signingString.getBytes)

            Base64.encode(sig.sign)
          }

          req ~> addHeader(HttpHeaders.Authorization(
            GenericHttpCredentials("Signature", "", Map(
              "keyId" -> keyId,
              "algorithm" -> algorithm,
              "headers" -> requiredHeaders.mkString(" "),
              "signature" -> signature(signingString).toString
            ))
          ))
        }

      val request = Post("/create?name=foo", "") ~>
        addHeader(HttpHeaders.Date(DateTime.now)) ~>
        addHeader(HttpHeaders.`Content-Length`(0)) ~>
        addSignature(Seq("(request-target)", "date", "content-length"))

      val verifier = new SignatureVerifier(keySet.toPublicJWKSet)

      verifier(request) must beRight

      verifier(
        request
          ~> removeHeader("Date")
          ~> addHeader(HttpHeaders.Date(DateTime(0)))) must beLeft[String]
    }
  }
}


