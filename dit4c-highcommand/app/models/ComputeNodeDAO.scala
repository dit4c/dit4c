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
import providers.machineshop.ContainerProvider

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

  def get(id: String) = list.map(nodes => nodes.find(_.id == id))

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

  case class ComputeNodeImpl(
      id: String,
      _rev: Option[String],
      name: String,
      managementUrl: String,
      backend: Hipache.Backend
      )(implicit ec: ExecutionContext) extends ComputeNode {
    import play.api.Play.current

    val containers = new ContainerProvider(
      managementUrl,
      () => keyDao.bestSigningKey.map(_.get.toJWK))

  }
}

trait ComputeNode {
  def id: String
  def _rev: Option[String]
  def name: String
  def managementUrl: String
  def backend: Hipache.Backend

  def containers: ContainerProvider

}

