package dit4c

import spray.http.HttpRequest
import com.nimbusds.jose.util.Base64
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import spray.http.HttpHeaders
import spray.http.GenericHttpCredentials

object HttpSignatureUtils {

  sealed class SigningAlgorithm(val httpName: String, val javaName: String) {
    def signatureInstance = Signature.getInstance(javaName)
  }
  object `RSA-SHA1` extends SigningAlgorithm("rsa-sha1", "SHA1withRSA")
  object `RSA-SHA256` extends SigningAlgorithm("rsa-sha256", "SHA256withRSA")

  def addSignature(
      keyId: String,
      privateKey: RSAPrivateKey,
      algorithm: SigningAlgorithm,
      requiredHeaders: Seq[String]): HttpRequest => HttpRequest =
    { req: HttpRequest =>
      import spray.httpx.RequestBuilding._

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

      req ~> addHeader(HttpHeaders.Authorization(
        GenericHttpCredentials("Signature", "", Map(
          "keyId" -> keyId,
          "algorithm" -> algorithm.httpName,
          "headers" -> requiredHeaders.mkString(" "),
          "signature" -> signature(signingString).toString
        ))
      ))
    }

}