package models

import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent
import scala.util.Try

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

case class ComputeNode(id: String, _rev: Option[String], name: String, url: String)(implicit ec: ExecutionContext) {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  import ComputeNode.Project

  object projects {

    def create(name: String): Future[Project] =
      WS.url(s"${url}projects/new")
        .post(Json.obj("name" -> name))
        .map(_.json.as[Project])

    def get(name: String): Future[Option[Project]] =
      WS.url(s"${url}projects/$name")
        .get()
        .map(r => Try(r.json.as[Project]).toOption)

    def list: Future[Seq[Project]] =
      WS.url(s"${url}projects").get().map { response =>
        response.json.asInstanceOf[JsArray].value.map(_.as[Project])
      }

  }

  class ProjectImpl(val name: String, val active: Boolean)(implicit ec: ExecutionContext) extends Project {

    override def start: Future[Project] =
      WS.url(s"${url}projects/$name/start")
        .post(EmptyContent())
        .map(_.json.as[Project])

    override def stop: Future[Project] =
      WS.url(s"${url}projects/$name/stop")
        .post(EmptyContent())
        .map(_.json.as[Project])

    override def delete: Future[Unit] =
      stop.flatMap { _ =>
        WS.url(s"${url}projects/$name")
          .delete()
          .flatMap { response =>
            if (response.status == 204) Future.successful[Unit](Unit)
            else Future.failed(
                new Exception(s"Deletion failed: ${response.status}"))
          }
      }

  }

  implicit val projectReads: Reads[Project] = (
    (__ \ "name").read[String] and
    (__ \ "active").read[Boolean]
  )((new ProjectImpl(_,_)))
}

object ComputeNode {
  trait Project {
    def name: String
    def active: Boolean
    def start: Future[Project]
    def stop: Future[Project]
    def delete: Future[Unit]
  }
}

