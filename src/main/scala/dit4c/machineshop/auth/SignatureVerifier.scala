package dit4c.machineshop.auth

import com.nimbusds.jose.jwk.JWKSet
import scala.collection.JavaConversions._
import spray.http.HttpRequest
import java.security.Signature
import spray.http.HttpHeaders
import spray.http.GenericHttpCredentials
import scala.collection.JavaConversions._
import com.nimbusds.jose.util.Base64

// https://web-payments.org/specs/source/http-signatures/
class SignatureVerifier(publicKeys: JWKSet) {

  def apply(request: HttpRequest): Either[String, Unit] = {
    extractSignatureInfo(request)
      .right.flatMap { info =>
        // Get public key from keyset
        Option(publicKeys.getKeyByKeyId(info.keyId)).map { key =>
          // Load public key, input signing string and verify
          info.algorithm.initVerify(
              key.asInstanceOf[com.nimbusds.jose.jwk.RSAKey].toRSAPublicKey)
          info.algorithm.update(info.signingString(request).getBytes)
          // Perform the validation
          if (info.algorithm.verify(info.signature)) {
            Right()
          } else {
            Left("HTTP Signature was invalid.")
          }
        }.getOrElse(Left("Public key with required ID was not found."))
      }
  }

  private def extractSignatureInfo(
      request: HttpRequest): Either[String, SignatureAuthorizationHeader] = {
    request.header[HttpHeaders.Authorization]
      .map(Right(_))
      .getOrElse(Left("No \"Authorization\" header present."))
      .right.flatMap { header =>
        header.credentials match {
          case c: GenericHttpCredentials if (c.scheme == "Signature") =>
            Right(c.params)
          case _ =>
            Left("Authorization is not a HTTP Signature.")
        }
      }
      .right.flatMap(SignatureAuthorizationHeader.fromParams)
  }

  private def getSignatureInstance(algorithm: String): Option[Signature] =
    (algorithm match {
      case "rsa-sha1"   => Some("SHA1withRSA")
      case "rsa-sha256" => Some("SHA256withRSA")
      case _ => None
    }).map(Signature.getInstance(_))

  private case class SignatureAuthorizationHeader(
        keyId: String,
        algorithm: java.security.Signature,
        headers: Seq[String],
        signature: Array[Byte]
      ) {
    def signingString(req: HttpRequest): String = {
      def values(name: String): Seq[String] =
        if (name == "(request-target)")
          Seq(s"${req.method.value.toLowerCase} ${req.uri.path}")
        else
          req.headers
            .filter(h => h.lowercaseName == name) // Headers with name
            .map(_.value.trim) // Convert to strings and trim (as per spec)
      // Fetch headers in order, repeating headers when necessary
      (for (hn <- headers; hv <- values(hn)) yield s"$hn: $hv").mkString("\n")
    }
  }

  private object SignatureAuthorizationHeader {
    def fromParams(
          m: Map[String, String]
        ): Either[String, SignatureAuthorizationHeader] = {
      val requiredParams = Set("keyId", "algorithm", "signature")
      if (requiredParams.forall(m.contains)) {
        val keyId = m("keyId")
        val possibleAlgorithm = getSignatureInstance(m("algorithm"))
        // "headers" is optional, with default of Date header only.
        // https://web-payments.org/specs/source/http-signatures/#headers
        val headers = m.get("headers").getOrElse("date").split(" ")
        val signature = (new Base64(m("signature"))).decode
        possibleAlgorithm.map { algorithm =>
          SignatureAuthorizationHeader(keyId, algorithm, headers, signature)
        }.toRight("Unsupported signature algorithm.")
      } else {
        Left(s"HTTP Signature header does not contain all required fields.")
      }
    }
  }

}