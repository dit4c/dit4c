package models

import play.api.libs.json._
import play.api.http.Writeable
import play.api.http.ContentTypes
import play.api.Logger
import play.twirl.api.JavaScript
import scala.concurrent.Future
import providers.db.CouchDB
import play.api.libs.ws._
import scala.concurrent.ExecutionContext

trait DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  implicit protected def ec: ExecutionContext
  protected def db: CouchDB.Database

  protected def fromJson[A](json: JsValue)(implicit reads: Reads[A]) : Option[A] =
    Json.fromJson[A](json)(reads) match {
      case JsSuccess(obj, _) => Some(obj)
      case JsError(messages) =>
        Logger.debug("Errors converting from JSON:\n"+messages.mkString("\n"))
        None
    }

  implicit class FormatWrapper[A](format: Format[A]) {
    def withTypeAttribute(typeName: String): Format[A] =
      Format(format, format.asInstanceOf[Writes[A]].withTypeAttribute(typeName))
  }

  implicit class WritesWrapper[A](writes: Writes[A]) {
    def withTypeAttribute(typeName: String): Writes[A] =
      writes.transform {
        // We need a type for searching
        _.as[JsObject] ++ Json.obj( "type" -> typeName )
      }
  }

  case class TemporaryView(val map: JavaScript)

  implicit val temporaryViewWrites = new Writes[TemporaryView] {
    override def writes(tv: TemporaryView) = Json.obj(
      "map" -> Json.toJson(tv.map)
    )
  }

  implicit val javascriptWrites: Writes[JavaScript] = new Writes[JavaScript] {
    override def writes(js: JavaScript) = JsString(js.body)
  }

  object utils {

    def create[M <: DAOModel[M]](newModelFunc: String => M)(
        implicit wjs: Writes[M]): Future[M] = {
      for {
        id <- db.newID
        newModel = newModelFunc(id)
        response <- WS.url(s"${db.baseURL}/${newModel.id}")
                      .put(Json.toJson(newModel))
      } yield {
        response.status match {
          case 201 =>
            // Update with revision
            val rev = (response.json \ "rev").as[String]
            newModel.revUpdate(rev)
        }
      }
    }

    def get[M <: DAOModel[M]](id: String)(
        implicit wjs: Writes[M], rjs: Reads[M]): Future[Option[M]] =
      WS.url(s"${db.baseURL}/$id").get.map { response =>
        (response.status match {
          case 200 => Some(response.json)
          case _ => None
        }).flatMap(fromJson[M])
      }

    def delete[M <: BaseModel](model: M): Future[Unit] =
      delete(model.id, model._rev.get)

    def delete(id: String, rev: String): Future[Unit] = {
      WS.url(s"${db.baseURL}/$id")
        .withHeaders("If-Match" -> rev)
        .delete
        .map { response =>
          if (response.status == 200) Unit
          else throw new Exception(
              s"Unexpected return code for DELETE: ${response.status}")
        }
    }

    def update[M <: DAOModel[M]](changed: => M)(
        implicit wjs: Writes[M]): Future[M] = {
      for {
        response <- WS.url(s"${db.baseURL}/${changed.id}")
                      .put(Json.toJson(changed))
      } yield {
        response.status match {
          case 201 =>
            // Update with revision
            val rev = (response.json \ "rev").as[String]
            changed.revUpdate(rev)
        }
      }
    }

  }

}

trait BaseModel {
  def id: String
  def _rev: Option[String]
}

trait DAOModel[M <: DAOModel[M]] extends BaseModel {
  def revUpdate(newRev: String): M
}

trait OwnableModel extends BaseModel {
  def ownerIDs: Set[String]
  def ownedBy(other: BaseModel): Boolean = ownerIDs.contains(other.id)
}

trait UsableModel extends BaseModel {
  def userIDs: Set[String]
  def usableBy(other: BaseModel): Boolean = userIDs.contains(other.id)
}