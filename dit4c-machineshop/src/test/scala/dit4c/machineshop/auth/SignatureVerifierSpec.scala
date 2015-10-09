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
import akka.http.scaladsl.client.RequestBuilding._
import akka.util.Timeout
import com.nimbusds.jose.util.Base64
import com.nimbusds.jose.util.Base64URL
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.actor.ActorSystem

class SignatureVerifierSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem("SignatureVerifierSpec")
  implicit val materializer = ActorMaterializer()
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
        val now = DateTime.now
        val request = Post("/container/foo/start", "") ~>
          addHeader(headers.Date(now)) ~>
          addHeader("Content-Length", 0L.toString) ~>
          addSignature(
              keyId, privateKey, algorithm,
              Seq("(request-target)", "date", "content-length"))

        // Check valid signature verifies
        verifier(request) must beRight
        // Check validation fails if we change the date
        verifier(
          request
            ~> removeHeader("Date")
            ~> addHeader(headers.Date(now + 1000))) must beLeft[String]
        // Check validation fails if date is outside a 5 minute skew
        verifier(
          Post("/container/foo/start", "") ~>
            addHeader(headers.Date(now - 3600000)) ~>
            addHeader("Content-Length", 0L.toString) ~>
            addSignature(
                keyId, privateKey, algorithm,
                Seq("(request-target)", "date", "content-length"))) must beLeft[String]
        // Check validation checks message digests
        import spray.httpx.SprayJsonSupport._
        import DefaultJsonProtocol._
        val testPayload =
          JsObject("name" -> JsString("test"), "image" -> JsString("test"))
        val alteredPayload =
          JsObject("name" -> JsString("changed"), "image" -> JsString("test"))
        verifier(
          Post("/container/new", testPayload ) ~>
            addHeader(headers.Date(now)) ~>
            addDigest("SHA-256") ~>
            addSignature(
                keyId, privateKey, algorithm,
                Seq("(request-target)", "date", "digest"))) must beRight
        verifier(
          Post("/container/new", testPayload) ~>
            addHeader(headers.Date(now)) ~>
            addDigest("SHA-256") ~>
            { req => req.withEntity(alteredPayload.prettyPrint) } ~>
            addSignature(
                keyId, privateKey, algorithm,
                Seq("(request-target)", "date", "digest"))) must beLeft[String]
      }

      done
    }
  }
}


