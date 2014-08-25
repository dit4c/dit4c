package models

import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent
import scala.util.Try
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import java.util.TimeZone
import play.api.libs.ws.WSRequestHolder
import com.nimbusds.jose.jwk.RSAKey
import java.security.Signature
import com.nimbusds.jose.util.Base64
import play.api.libs.ws.InMemoryBody
import java.security.MessageDigest
import providers.hipache.Hipache

class ComputeNodeDAO @Inject() (
    protected val db: CouchDB.Database,
    protected val keyDao: KeyDAO
    )(implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  def create(name: String, managementUrl: String, backend: Hipache.Backend): Future[ComputeNode] =
    db.newID.flatMap { id =>
      val node = ComputeNodeImpl(id, None, name, managementUrl, backend)
      WS.url(s"${db.baseURL}/$id").put(Json.toJson(node)).map { response =>
        response.status match {
          case 201 => node
        }
      }
    }

  def list: Future[Seq[ComputeNode]] = {
    val tempView = TemporaryView(views.js.models.ComputeNode_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[ComputeNodeImpl])
      }
  }

  implicit val hipacheBackendFormat: Format[Hipache.Backend] = (
    (__ \ "host").format[String] and
    (__ \ "port").format[Int] and
    (__ \ "scheme").format[String]
  )(Hipache.Backend.apply _, unlift(Hipache.Backend.unapply))

  implicit val computeNodeFormat: Format[ComputeNodeImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "managementURL").format[String] and
    (__ \ "backend").format[Hipache.Backend]
  )(ComputeNodeImpl.apply _, unlift(ComputeNodeImpl.unapply))
    .withTypeAttribute("ComputeNode")

  def withPrivateKey[A](f: RSAKey => Future[A]): Future[A] =
    keyDao.bestSigningKey.map(_.get.toJWK).flatMap(f)

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

  case class ComputeNodeImpl(
      id: String,
      _rev: Option[String],
      name: String,
      managementUrl: String,
      backend: Hipache.Backend
      )(implicit ec: ExecutionContext) extends ComputeNode {
    import play.api.libs.functional.syntax._

    import play.api.Play.current

    import ComputeNode.Container

    object containers extends ComputeNode.Containers {

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

    }

    class ContainerImpl(
        val name: String,
        val active: Boolean
        )(implicit ec: ExecutionContext) extends Container {

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

      override def proxyBackend: Hipache.Backend = backend

      override def toString = s"ComputeNode.Container($name, $active)"

    }

    private def ws(path: String) = WS.url(s"$managementUrl$path")

    implicit val containerReads: Reads[Container] = (
      (__ \ "name").read[String] and
      (__ \ "active").read[Boolean]
    )((new ContainerImpl(_,_)))
  }
}

trait ComputeNode {
  def id: String
  def _rev: Option[String]
  def name: String
  def managementUrl: String
  def backend: Hipache.Backend

  def containers: ComputeNode.Containers

}

object ComputeNode {
  trait Containers {
    def create(name: String, image: String): Future[ComputeNode.Container]
    def get(name: String): Future[Option[ComputeNode.Container]]
    def list: Future[Seq[ComputeNode.Container]]
  }

  trait Container {
    def name: String
    def active: Boolean

    def proxyBackend: Hipache.Backend

    def start: Future[Container]
    def stop: Future[Container]
    def delete: Future[Unit]
  }
}

