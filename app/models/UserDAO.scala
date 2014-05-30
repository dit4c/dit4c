package models

import providers.db.CouchDB
import play.api.libs.ws.WS
import providers.auth._
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import scala.concurrent.Future
import play.api.templates.JavaScript

class UserDAO(protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._

  def createWith(identity: Identity): Future[User] =
    db.newID.flatMap { id =>
      val user =
        UserImpl(id, None,
            identity.name, identity.emailAddress, Seq(identity.uniqueId))
      val data = Json.toJson(user)
      val holder = WS.url(s"${db.baseURL}/$id")
      holder.put(data).map { response =>
        response.status match {
          case 201 => user
        }
      }
    }

  def findWith(identity: Identity): Future[Option[User]] = {
    val tempView =
      TemporaryView(views.js.models.User_findWith_map(identity.uniqueId))
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value")
          .headOption
          .flatMap(fromJson[UserImpl])
      }
  }

  def get(id: String): Future[Option[User]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap(fromJson[UserImpl])
    }

  implicit val userFormat: Format[UserImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").formatNullable[String] and
    (__ \ "email").formatNullable[String] and
    (__ \ "identities").format[Seq[String]]
  )(UserImpl.apply _, unlift(UserImpl.unapply))
    .withTypeAttribute("User")

  implicit class ReadCombiner[A](r1: Reads[A]) {
    def or(r2: Reads[A]) = new Reads[A] {
      override def reads(json: JsValue) =
        r1.reads(json) match {
          case result: JsSuccess[A] => result
          case _: JsError => r2.reads(json)
        }
    }
  }

  case class UserImpl(
    val id: String,
    val _rev: Option[String],
    val name: Option[String],
    val email: Option[String],
    val identities: Seq[String]) extends User

}

trait User {
  def id: String
  def _rev: Option[String]
  def name: Option[String]
  def email: Option[String]
  def identities: Seq[String]
}