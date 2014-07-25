package dit4c.machineshop.auth

import com.nimbusds.jose.jwk.JWKSet
import scala.collection.JavaConversions._
import spray.http.HttpRequest
import java.security.Signature
import spray.http.HttpHeaders
import spray.http.GenericHttpCredentials
import scala.collection.JavaConversions._
import com.nimbusds.jose.util.Base64
import spray.http.DateTime
import java.security.MessageDigest

// https://web-payments.org/specs/source/http-signatures/
class SignatureVerifier(publicKeys: JWKSet) {

  // Maximum skew of Date header from current time (in milliseconds)
  val maximumSkew = 300000

  def apply(request: HttpRequest): Either[String, Unit] = {
    validateDate(request)
      .right.flatMap(validateDigest)
      .right.flatMap(extractSignatureInfo)
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

  private def validateDate(request: HttpRequest): Either[String, HttpRequest] =
    request.header[HttpHeaders.Date] match {
      case None => Right(request) // Dates aren't required on requests
      case Some(header) =>
        if (header.date.insideSkew(DateTime.now, maximumSkew))
          Right(request)
        else
          Left(s"Date header is skewed more than $maximumSkew milliseconds.")
    }

  private def validateDigest(request: HttpRequest): Either[String, HttpRequest] = {
    val digests: Seq[(String, String)] =
      request.headers
        .filter(_.lowercaseName == "digest")
        .map(_.value)
        .map { v =>
          val Seq(alg, b64digest) = v.split("=").toSeq
          (alg, b64digest)
        }
    if (digests.isEmpty)
      Right(request) // No digest, no problem
    else {
      val errors = digests
        .map { case(alg, b64digest) =>
          val digest = MessageDigest.getInstance(alg)
          digest.update(request.entity.data.toByteArray)
          if (digest.digest == new Base64(b64digest).decode) {
            None
          } else {
            Some(s"The $alg message digest did not match.")
          }
        }
        .flatten
      if (errors.isEmpty)
        Right(request)
      else
        Left(errors.mkString("\n"))
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

  private implicit class DateTimeSkewHelper(dt: DateTime) {

    // Adding skew should make it later than other,
    // while removing skew should make it earlier than other
    def insideSkew(other: DateTime, millis: Long): Boolean =
      (dt + millis) > other && (dt - millis) < other

  }

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