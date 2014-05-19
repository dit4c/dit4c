package models

import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent

class ComputeNodeDAO(db: CouchDB.Database)(implicit ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._

  def create(name: String, url: String): Future[ComputeNode] =
    db.newID.flatMap { id =>
      val node = ComputeNode(id, name, url)
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
    (__ \ "name").format[String] and
    (__ \ "url").format[String]
  )(ComputeNode.apply _, unlift(ComputeNode.unapply))
    .withTypeAttribute("ComputeNode")

}

case class ComputeNode(_id: String, name: String, url: String) {
  import play.api.libs.functional.syntax._

  object projects {

    def create(name: String)(implicit ec: ExecutionContext): Future[Project] = {
      val json = Json.obj("name" -> name)
      WS.url(s"${url}projects/new").post(json).map { response =>
        response.json.as[Project]
      }
    }

    def list(implicit ec: ExecutionContext): Future[Seq[Project]] =
      WS.url(s"${url}projects").get().map { response =>
        response.json.asInstanceOf[JsArray].value.map(_.as[Project])
      }

  }

  implicit val projectReads: Reads[Project] = (
    (__ \ "name").read[String] and
    (__ \ "active").read[Boolean]
  )(Project)

  case class Project(name: String, active: Boolean) {

    def start(implicit ec: ExecutionContext): Future[Project] =
      WS.url(s"${url}projects/$name/start").post(EmptyContent()).map { response =>
        response.json.as[Project]
      }

    def stop(implicit ec: ExecutionContext): Future[Project] =
      WS.url(s"${url}projects/$name/stop").post(EmptyContent()).map { response =>
        response.json.as[Project]
      }

  }
}