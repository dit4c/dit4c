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

  def create(user: User, name: String, description: String): Future[Project] =
    list.flatMap { projects =>
      if (projects.exists(_.name == name)) {
        throw new Exception("Project with that name already exists.")
      }
      db.newID.flatMap { id =>
        val node = ProjectImpl(id, None, name, description, Set(user.id))
        WS.url(s"${db.baseURL}/$id")
          .put(Json.toJson(node))
          .flatMap { response =>
            response.status match {
              case 201 => get(id).map(_.get)
            }
          }
      }
    }

  def get(id: String): Future[Option[Project]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap(fromJson[ProjectImpl])
    }

  def list: Future[Seq[Project]] = {
    val tempView = TemporaryView(views.js.models.Project_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[ProjectImpl])
      }
  }

  implicit val projectFormat: Format[ProjectImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "description").format[String] and
    (__ \ "ownerIDs").format[Set[String]]
  )(ProjectImpl.apply _, unlift(ProjectImpl.unapply))
    .withTypeAttribute("Project")


  case class ProjectImpl(
      id: String,
      _rev: Option[String],
      name: String,
      description: String,
      ownerIDs: Set[String]) extends Project {

    override def delete: Future[Unit] = utils.delete(id, _rev.get)

  }

}

trait Project {

  def id: String
  def _rev: Option[String]
  def name: String
  def description: String
  def ownerIDs: Set[String]

  def delete: Future[Unit]

}