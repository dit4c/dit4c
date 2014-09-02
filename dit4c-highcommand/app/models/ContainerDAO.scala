package models

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import play.api.libs.ws._
import play.api.libs.json._

class ContainerDAO(protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  def create(
      user: User,
      name: String,
      image: String,
      computeNode: ComputeNode): Future[Container] =
    list.flatMap { containers =>
      if (containers.exists(_.name == name)) {
        throw new Exception("Container with that name already exists.")
      }
      db.newID.flatMap { id =>
        val node = ContainerImpl(id, None, name, image, computeNode.id,
          Set(user.id))
        WS.url(s"${db.baseURL}/$id")
          .put(Json.toJson(node))
          .flatMap { response =>
            response.status match {
              case 201 => get(id).map(_.get)
            }
          }
      }
    }

  def get(id: String): Future[Option[Container]] =
    WS.url(s"${db.baseURL}/$id").get.map { response =>
      (response.status match {
        case 200 => Some(response.json)
        case _ => None
      }).flatMap(fromJson[ContainerImpl])
    }

  def list: Future[Seq[Container]] = {
    val tempView = TemporaryView(views.js.models.Container_list_map())
    WS.url(s"${db.baseURL}/_temp_view")
      .post(Json.toJson(tempView))
      .map { response =>
        (response.json \ "rows" \\ "value").flatMap(fromJson[ContainerImpl])
      }
  }

  implicit val projectFormat: Format[ContainerImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "image").format[String] and
    (__ \ "computeNodeId").format[String] and
    (__ \ "ownerIDs").format[Set[String]]
  )(ContainerImpl.apply _, unlift(ContainerImpl.unapply))
    .withTypeAttribute("Container")


  case class ContainerImpl(
      id: String,
      _rev: Option[String],
      name: String,
      image: String,
      computeNodeId: String,
      ownerIDs: Set[String]) extends Container {

    override def delete: Future[Unit] = utils.delete(id, _rev.get)

  }

}

trait Container extends OwnableModel {

  def name: String
  def image: String
  def computeNodeId: String

  def delete: Future[Unit]

}