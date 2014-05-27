package models

import play.api.libs.json._
import play.api.templates.JavaScript
import play.api.http.Writeable
import play.api.http.ContentTypes
import play.api.Logger
import scala.concurrent.Future
import providers.db.CouchDB
import play.api.libs.ws._
import scala.concurrent.ExecutionContext

trait DAOUtils {
  import play.api.libs.functional.syntax._

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
  }

}