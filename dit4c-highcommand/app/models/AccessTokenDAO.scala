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
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import java.security.interfaces.RSAPublicKey
import java.security.KeyPairGenerator
import com.nimbusds.jose.JWSAlgorithm
import scala.util.Random

class AccessTokenDAO @Inject() (protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current
  import AccessToken._

  val newCodeLength = 12
  val validCodeChars: IndexedSeq[Char] = ('0' to '9') ++ ('A' to 'Z')

  def create(
      accessType: AccessType.Value,
      computeNode: ComputeNode): Future[AccessToken] =
    utils.create { id =>
      AccessTokenImpl(id, None, newCode, accessType, new Resource(computeNode))
    }

  def get(id: String): Future[Option[AccessToken]] = utils.get(id)

  def listFor(computeNode: ComputeNode): Future[Seq[AccessToken]] = {
    val tempView = TemporaryView(views.js.models.AccessToken_listFor_map(
        computeNode.id))
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[AccessTokenImpl])
      }
  }

  protected def newCode: String = codeChars.take(newCodeLength).mkString
  protected def codeChars: Stream[Char] = random(validCodeChars) #:: codeChars
  protected def random(cs: IndexedSeq[Char]) = cs(Random.nextInt(cs.length))

  implicit val accessTypeFormat = new Format[AccessType.Value] {
    def reads(json: JsValue) =
      try {
        JsSuccess(AccessType.withName(json.as[String]))
      } catch {
        case _: NoSuchElementException =>
          JsError(JsPath(), "Unknown access type value")
      }

    def writes(o: AccessType.Value): JsValue = JsString(o.toString)
  }

  implicit val resourceTypeFormat = new Format[ResourceType.Value] {
    def reads(json: JsValue) =
      try {
        JsSuccess(ResourceType.withName(json.as[String]))
      } catch {
        case _: NoSuchElementException =>
          JsError(JsPath(), "Unknown resource type value")
      }

    def writes(o: ResourceType.Value): JsValue = JsString(o.toString)
  }

  implicit val resourceFormat: Format[Resource] = (
    (__ \ "id").format[String] and
    (__ \ "type").format[ResourceType.Value]
  )(Resource.apply _, unlift(Resource.unapply))

  implicit private val accessTokenFormat: Format[AccessTokenImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "code").format[String] and
    (__ \ "accessType").format[AccessType.Value] and
    (__ \ "resource").format[Resource]
  )(AccessTokenImpl.apply _, unlift(AccessTokenImpl.unapply))
    .withTypeAttribute("AccessToken")

  case class AccessTokenImpl(
      id: String,
      _rev: Option[String],
      code: String,
      accessType: AccessType.Value,
      resource: Resource
    ) extends AccessToken with DAOModel[AccessTokenImpl] {

    override def revUpdate(newRev: String) = this.copy(_rev = Some(newRev))

    def delete: Future[Unit] = utils.delete(this)

  }

}

trait AccessToken extends BaseModel {
  import AccessToken._

  def code: String
  def accessType: AccessType.Value
  def resource: Resource

  def delete: Future[Unit]

}

object AccessToken {

  object AccessType extends Enumeration {
    val Share = Value
  }

  object ResourceType extends Enumeration {
    val ComputeNode = Value
  }

  case class Resource(id: String, `type`: ResourceType.Value) {

    def this(node: ComputeNode) = this(node.id, ResourceType.ComputeNode)

  }

}

