package providers.machineshop

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import play.api.libs.ws.WSRequestHolder
import play.api.libs.json._
import scala.util.Try
import java.util.Date
import java.util.TimeZone
import play.api.libs.ws.WSRequestHolder
import com.nimbusds.jose.jwk.RSAKey
import java.security.Signature
import com.nimbusds.jose.util.Base64
import play.api.libs.ws.InMemoryBody
import java.security.MessageDigest
import play.api.libs.ws.WS

class ContainerProvider(
    managementUrl: String,
    privateKey: () => Future[RSAKey]
  )(implicit executionContext: ExecutionContext) {

  import play.api.libs.functional.syntax._
  import play.api.Play.current
  import ContainerProvider._
  import MachineShop.Container

  def create(name: String, image: String) = withPrivateKey { key =>
    ws("containers/new")
      .withMethod("POST")
      .withHeaders("Content-Type" -> "application/json; charset=utf-8")
      .withBody(InMemoryBody(Json.stringify(
          Json.obj("name" -> name, "image" -> image)).getBytes))
      .calculateDigest
      .httpSign(key)
      .execute()
      .map(_.json.as[Container])
  }

  def get(name: String): Future[Option[Container]] =
    ws(s"containers/$name")
      .get()
      .map(r => Try(r.json.as[Container]).toOption)

  def list: Future[Seq[Container]] =
    ws("containers").get().map { response =>
      response.json.asInstanceOf[JsArray].value.map(_.as[Container])
    }

  class ContainerImpl(
      val name: String,
      val active: Boolean
      )(implicit executionContext: ExecutionContext) extends Container {

    override def start: Future[Container] = withPrivateKey { key =>
      ws(s"containers/$name/start")
        .withMethod("POST")
        .httpSign(key)
        .execute()
        .map(_.json.as[Container])
    }

    override def stop: Future[Container] = withPrivateKey { key =>
      ws(s"containers/$name/stop")
        .withMethod("POST")
        .httpSign(key)
        .execute()
        .map(_.json.as[Container])
    }

    override def delete: Future[Unit] = withPrivateKey { key =>
      stop.flatMap { _ =>
        ws(s"containers/$name")
          .withMethod("DELETE")
          .httpSign(key)
          .execute()
          .flatMap { response =>
            if (response.status == 204) Future.successful[Unit](Unit)
            else Future.failed(
                new Exception(s"Deletion failed: ${response.status}"))
          }
      }
    }

    override def toString = s"ContainerProvider.Container($name, $active)"

  }

  implicit val containerReads: Reads[Container] = (
    (__ \ "name").read[String] and
    (__ \ "active").read[Boolean]
  )((new ContainerImpl(_,_)))

  protected def withPrivateKey[A](f: RSAKey => Future[A]): Future[A] =
    privateKey().flatMap(f)

  protected def ws(path: String) = WS.url(s"$managementUrl$path")

}

object ContainerProvider {
  implicit class HttpSignatureCalculator(request: WSRequestHolder) {
    def httpSign(privateKey: RSAKey): WSRequestHolder = {
      def now = {
        val sdf = new java.text.SimpleDateFormat(
            "E, d MMM yyyy HH:mm:ss z", java.util.Locale.US)
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

    def calculateDigest: WSRequestHolder = request.body match {
      case InMemoryBody(bytes) =>
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        request.withHeaders(
          "Digest" -> s"SHA-256=${Base64.encode(digest.digest)}"
        )
      case _ => request // Can't calculate easily
    }
  }
}