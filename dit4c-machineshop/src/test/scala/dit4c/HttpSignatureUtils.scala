package dit4c

import com.nimbusds.jose.util.Base64
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.GenericHttpCredentials
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.ByteString
import akka.stream.Materializer
import scala.concurrent.ExecutionContext

object HttpSignatureUtils {

  sealed class SigningAlgorithm(val httpName: String, val javaName: String) {
    def signatureInstance = Signature.getInstance(javaName)
  }
  object `RSA-SHA1` extends SigningAlgorithm("rsa-sha1", "SHA1withRSA")
  object `RSA-SHA256` extends SigningAlgorithm("rsa-sha256", "SHA256withRSA")

  def addDigest(algorithm: String)(
      implicit ec: ExecutionContext, materializer: Materializer):
      HttpRequest => HttpRequest = { req =>
    val requestBytes = Await.result(
          req.entity.dataBytes
            .runFold(ByteString.empty)((a,b) => a++b)
            .map(_.toArray),
          5.seconds)
    val digest: String = {
      val digest = MessageDigest.getInstance(algorithm)
      digest.update(requestBytes)
      val b64 = Base64.encode(digest.digest)
      s"${algorithm}=${b64}"
    }
    val digestHeader =
        headers.RawHeader("Digest", digest)
    req ~> addHeader(digestHeader)
  }

  def addSignature(
      keyId: String,
      privateKey: RSAPrivateKey,
      algorithm: SigningAlgorithm,
      requiredHeaders: Seq[String]): HttpRequest => HttpRequest =
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

      def signature(signingString: String): Base64 = {
        def getSignatureInstance(alg: String): Signature =
          Signature.getInstance(alg match {
            case "rsa-sha1"   => "SHA1withRSA"
            case "rsa-sha256" => "SHA256withRSA"
          })

        val sig = algorithm.signatureInstance
        sig.initSign(privateKey)
        sig.update(signingString.getBytes)

        Base64.encode(sig.sign)
      }

      req ~> addHeader(headers.Authorization(
        GenericHttpCredentials("Signature", "", Map(
          "keyId" -> keyId,
          "algorithm" -> algorithm.httpName,
          "headers" -> requiredHeaders.mkString(" "),
          "signature" -> signature(signingString).toString
        ))
      ))
    }

}