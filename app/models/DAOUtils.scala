package models

import play.api.libs.json._
import play.api.templates.JavaScript
import play.api.http.Writeable
import play.api.http.ContentTypes

trait DAOUtils {
  import play.api.libs.functional.syntax._

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

}