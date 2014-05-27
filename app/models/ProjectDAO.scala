package models

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import play.api.libs.ws._
import play.api.libs.json._

class ProjectDAO(protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._

  def create(name: String, description: String): Future[Project] =
    db.newID.flatMap { id =>
      val node = Project(id, None, name, description)
      WS.url(s"${db.baseURL}/$id").put(Json.toJson(node)).flatMap { response =>
        response.status match {
          case 201 => get(id).map(_.get)
        }
      }
    }

  def get(id: String): Future[Option[Project]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap(fromJson[Project])
    }

  implicit val projectFormat: Format[Project] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "description").format[String]
  )(Project.apply _, unlift(Project.unapply))
    .withTypeAttribute("Project")

  case class Project(id: String, _rev: Option[String], name: String, description: String) {

    def delete: Future[Unit] = utils.delete(id, _rev.get)

  }

}