package models

import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._

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
    val tempView = TemporaryView(models.views.js.ComputeNode_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap { v =>
          Json.fromJson[ComputeNode](v) match {
            case JsSuccess(node, _) => Some(node)
            case _ => None
          }
        }
      }
  }

  implicit val computeNodeReads: Reads[ComputeNode] = (
    (__ \ "_id").read[String] and
    (__ \ "name").read[String] and
    (__ \ "url").read[String]
  )(ComputeNode)

  implicit val computeNodeWrites: Writes[ComputeNode] = (
    (__ \ "_id").write[String] and
    (__ \ "name").write[String] and
    (__ \ "url").write[String]
  )(unlift(ComputeNode.unapply)).transform {
    // We need a type for searching
    _.as[JsObject] ++ Json.obj( "type" -> "ComputeNode" )
  }

}

case class ComputeNode(_id: String, name: String, url: String) {

  def projects(implicit ec: ExecutionContext): Future[Seq[String]] =
    WS.url(s"${url}projects").get().map { response =>
      (response.json \\ "name").map(_.as[String])
    }

}