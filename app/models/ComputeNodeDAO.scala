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

class ComputeNodeDAO @Inject() (protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  def create(name: String, url: String): Future[ComputeNode] =
    db.newID.flatMap { id =>
      val node = ComputeNode(id, None, name, url)
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
        (response.json \ "rows" \\ "value").flatMap(fromJson[ComputeNode])
      }
  }

  implicit val computeNodeFormat: Format[ComputeNode] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "url").format[String]
  )(ComputeNode.apply _, unlift(ComputeNode.unapply))
    .withTypeAttribute("ComputeNode")

}

object ComputeNodeDAO {
  implicit class HttpSignatureCalculator(request: WSRequestHolder) {
    def httpSign(): WSRequestHolder = {
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
      val signingLines =
        s"(request-target): ${req.method.toLowerCase} $uri" ::
        req.headers("Date").map(d => s"date: $d").toList :::
        Nil
      val signingString = signingLines.mkString("\n")

      val params = Map(
        "keyId" -> "Replace this later",
        "algorithm" -> "rsa-sha256",
        "headers" -> "(request-target) date",
        "signature" -> ""
      )

      req.withHeaders(
        "Authorization" -> ("Signature " +
          params.map({case (k,v) => s"""$k="$v""""}).mkString(","))
      )
    }
  }
}




case class ComputeNode(id: String, _rev: Option[String], name: String, url: String)(implicit ec: ExecutionContext) {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  import ComputeNode.Container

  import ComputeNodeDAO.HttpSignatureCalculator

  object containers {

    def create(name: String, image: String): Future[Container] =
      WS.url(s"${url}containers/new")
        .httpSign()
        .post(Json.obj("name" -> name, "image" -> image))
        .map(_.json.as[Container])

    def get(name: String): Future[Option[Container]] =
      WS.url(s"${url}containers/$name")
        .get()
        .map(r => Try(r.json.as[Container]).toOption)

    def list: Future[Seq[Container]] =
      WS.url(s"${url}containers").get().map { response =>
        response.json.asInstanceOf[JsArray].value.map(_.as[Container])
      }

  }

  class ContainerImpl(val name: String, val active: Boolean)(implicit ec: ExecutionContext) extends Container {

    override def start: Future[Container] =
      WS.url(s"${url}containers/$name/start")
        .httpSign
        .post(EmptyContent())
        .map(_.json.as[Container])

    override def stop: Future[Container] =
      WS.url(s"${url}containers/$name/stop")
        .httpSign
        .post(EmptyContent())
        .map(_.json.as[Container])

    override def delete: Future[Unit] =
      stop.flatMap { _ =>
        WS.url(s"${url}containers/$name")
          .httpSign
          .delete()
          .flatMap { response =>
            if (response.status == 204) Future.successful[Unit](Unit)
            else Future.failed(
                new Exception(s"Deletion failed: ${response.status}"))
          }
      }

  }

  implicit val containerReads: Reads[Container] = (
    (__ \ "name").read[String] and
    (__ \ "active").read[Boolean]
  )((new ContainerImpl(_,_)))
}

object ComputeNode {
  trait Container {
    def name: String
    def active: Boolean
    def start: Future[Container]
    def stop: Future[Container]
    def delete: Future[Unit]
  }
}

