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

class KeyDAO @Inject() (protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  /**
   * @param namespace   Arbitrary string to include in key IDs
   * @param keyLength   Length of key to generate (default: 4096)
   * @return Future key object
   */
  def create(namespace: String, keyLength: Int = 4096): Future[Key] = {
    for {
      id <- db.newID
      keyPair <- createNewKeyPair(keyLength)
      createdAt = DateTime.now(DateTimeZone.UTC)
      key = KeyImpl(id, None, namespace, createdAt, false, keyPair)
      response <- WS.url(s"${db.baseURL}/$id").put(Json.toJson(key))
    } yield {
      response.status match {
        case 201 =>
          // Update with revision
          val rev = (response.json \ "rev").as[Option[String]]
          key.copy(_rev = rev)
      }
    }
  }

  /**
   * @returns Oldest non-retired key (which may not exist)
   */
  def bestSigningKey: Future[Option[Key]] =
    list.map { keys =>
      keys
        .filter(!_.retired)
        .sortBy(_.createdAt.toString())
        .headOption
    }


  def list: Future[Seq[Key]] = {
    val tempView = TemporaryView(views.js.models.Key_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[KeyImpl])
      }
  }

  // RSAKey isn't comparable by default, so we wrap it. (RSAKey is final.)
  private class WrappedRSAKey(val rsaKey: RSAKey) {

    override def equals(o: Any) = o match {
      case other: WrappedRSAKey => this.json == other.json
      case _ => false
    }

    override def hashCode = json.hashCode

    lazy val json = Json.parse(rsaKey.toJSONString)

  }

  private def createNewKeyPair(keyLength: Int): Future[WrappedRSAKey] = Future {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(keyLength)
    val kp = generator.generateKeyPair
    val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
    val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
    new WrappedRSAKey(
        new RSAKey(pub, priv, null, null, null, null, null, null, null))
  }

  implicit val dateTimeFormat: Format[DateTime] = new Format[DateTime]() {
    def reads(json: JsValue): JsResult[DateTime] =
      try {
        JsSuccess(DateTime.parse(json.as[String]))
      } catch {
        case _: IllegalArgumentException => JsError()
      }

    def writes(o: DateTime): JsValue =
      JsString(o.toString)
  }

  implicit private val rsaKeyFormat: Format[WrappedRSAKey] =
    new Format[WrappedRSAKey]() {
      def reads(json: JsValue): JsResult[WrappedRSAKey] =
        JsSuccess(new WrappedRSAKey(
            JWK.parse(Json.stringify(json)).asInstanceOf[RSAKey]))

      def writes(o: WrappedRSAKey): JsValue = o.json
    }

  implicit private val keyFormat: Format[KeyImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "namespace").format[String] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "retired").format[Boolean] and
    (__ \ "keyPair").format[WrappedRSAKey]
  )(KeyImpl.apply _, unlift(KeyImpl.unapply))
    .withTypeAttribute("Key")

  private case class KeyImpl(
      id: String,
      _rev: Option[String],
      namespace: String,
      createdAt: DateTime,
      retired: Boolean,
      keyPair: WrappedRSAKey)(implicit ec: ExecutionContext)
      extends Key with DAOModel[KeyImpl] {

    import play.api.libs.functional.syntax._
    import play.api.Play.current

    override def publicId: String = s"$namespace $createdAt [$id]"

    override def retire: Future[Key] = utils.update {
      this.copy(retired = true)
    }

    override def delete: Future[Unit] = utils.delete(id, _rev.get)

    override def toJWK = new RSAKey(
      keyPair.rsaKey.toRSAPublicKey,
      keyPair.rsaKey.toRSAPrivateKey,
      keyPair.rsaKey.getKeyUse,
      keyPair.rsaKey.getKeyOperations,
      keyPair.rsaKey.getAlgorithm,
      publicId,
      null, null, null)

    override def revUpdate(newRev: String) = this.copy(_rev = Some(newRev))

  }

}

trait Key {

  def id: String
  def _rev: Option[String]
  def namespace: String
  def createdAt: DateTime
  def retired: Boolean

  def publicId: String
  def toJWK: RSAKey

  def retire: Future[Key]
  def delete: Future[Unit]

}

