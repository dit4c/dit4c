package models

import providers.db.CouchDB
import play.api.libs.ws.WS
import providers.auth.Identity
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import scala.concurrent.Future
import play.api.templates.JavaScript

class UserDAO(db: CouchDB.Database)(implicit ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._

  def createWith(identity: Identity): Future[User] =
    db.newID.flatMap { id =>
      val user = User(id, Seq(identity.uniqueId))
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
        (response.json \ "rows" \\ "value").headOption.flatMap { v =>
          Json.fromJson[User](v) match {
            case JsSuccess(user, _) => Some(user)
            case _ => None
          }
        }
      }
  }

  def get(id: String): Future[Option[User]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap { json =>
        Json.fromJson[User](json) match {
          case JsSuccess(user, _) => Some(user)
          case _ => None
        }
      }
    }

  implicit val userReads: Reads[User] = (
    (__ \ "_id").read[String] and
    (__ \ "identities").read[Seq[String]]
  )(User)

  implicit val userWrites: Writes[User] = (
    (__ \ "_id").write[String] and
    (__ \ "identities").write[Seq[String]]
  )(unlift(User.unapply)).transform {
    // We need a type for searching
    _.as[JsObject] ++ Json.obj( "type" -> "User" )
  }

}


case class User(val _id: String, val identities: Seq[String])