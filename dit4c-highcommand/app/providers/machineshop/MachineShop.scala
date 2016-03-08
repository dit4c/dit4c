package providers.machineshop

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.Try
import java.security.MessageDigest
import java.security.Signature
import java.util.Date
import java.util.TimeZone
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64
import play.api.libs.ws._
import scala.concurrent.duration._

object MachineShop {
  trait Container {
    def name: String
    def active: Boolean

    def start: Future[Container]
    def stop: Future[Container]
    def delete: Future[Unit]
  }

  def fetchServerId(
      managementUrl: String
      )(implicit executionContext: ExecutionContext): Future[String] = {
    import play.api.Play.current
    val path = "server-id"
    for {
      call <- Future.fromTry(Try(WS.url(s"$managementUrl$path")))
      response <- call.get
    } yield response.body.trim
  }

  class Client(
      managementUrl: String,
      privateKeyProvider: () => Future[RSAKey]
      )(implicit executionContext: ExecutionContext) {

    import play.api.Play.current

    def apply(path: String, timeout: FiniteDuration = 2.minutes) =
      new RequestHolder(WS.url(s"$managementUrl$path")
          .withRequestTimeout(timeout))

    class RequestHolder(ws: WSRequest) {
      import play.api.libs.iteratee.Enumerator
      type PrepFunc = WSRequest => WSRequest
      type StreamTuple = (WSResponseHeaders, Enumerator[Array[Byte]])

      def signed(prepareFunc: PrepFunc): Future[WSResponse] =
        for {
          request <- signedNoExec(prepareFunc)
          response <- request.execute()
        } yield response

      def signedAsStream(prepareFunc: PrepFunc): Future[StreamTuple] =
        for {
          request <- signedNoExec(prepareFunc)
          stream <- request.getStream()
        } yield stream

      protected def signedNoExec(f: PrepFunc): Future[WSRequest] =
        for {
          key <- privateKeyProvider()
        } yield f(ws).calculateDigest.httpSign(key)

      def unsigned(prepareFunc: PrepFunc): Future[WSResponse] =
        prepareFunc(ws).calculateDigest.execute()

    }

  }

  implicit class HttpSignatureCalculator(request: WSRequest) {
    def httpSign(privateKey: RSAKey): WSRequest = {
      def now = {
        val sdf = new java.text.SimpleDateFormat(
            "E, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"))
        sdf.format(new Date())
      }

      // Horribly procedural - rewrite it later
      var req = request

      if (req.headers.get("Date").isEmpty) {
        req = req.withHeaders("Date" -> now)
      }

      val uriPattern = """^\w+\:\/\/[^/]+(.+)$""".r
      val uriPattern(uri) = req.url
      val headerNames =
        if (req.headers.contains("Digest")) List("Date", "Digest")
        else List("Date")

      val signingLines =
        s"(request-target): ${req.method.toLowerCase} $uri" ::
        headerNames.map { hn =>
          req.headers(hn).map(hv => s"${hn.toLowerCase}: $hv").toList
        }.reduce(_ ++ _)
      val signingString = signingLines.mkString("\n")

      val sig = Signature.getInstance("SHA256withRSA")
      sig.initSign(privateKey.toRSAPrivateKey)
      sig.update(signingString.getBytes)

      val params: Map[String, String] = Map(
        "keyId" -> privateKey.getKeyID,
        "algorithm" -> "rsa-sha256",
        "headers" -> ("(request-target)" :: headerNames).map(_.toLowerCase).mkString(" "),
        "signature" -> Base64.encode(sig.sign).toString
      )

      req.withHeaders(
        "Authorization" -> ("Signature " +
          params.map({case (k,v) => s"""$k="$v""""}).mkString(","))
      )
    }

    def calculateDigest: WSRequest = request.body match {
      case InMemoryBody(bytes) =>
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes.toArray)
        request.withHeaders(
          "Digest" -> s"SHA-256=${Base64.encode(digest.digest)}"
        )
      case _ => request // Can't calculate easily
    }
  }

  case class Error(msg: String) extends Exception(msg)

}