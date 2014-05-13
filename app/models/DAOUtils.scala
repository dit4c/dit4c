package models

import play.api.libs.json._
import play.api.templates.JavaScript
import play.api.http.Writeable
import play.api.http.ContentTypes

trait DAOUtils {
  import play.api.libs.functional.syntax._

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