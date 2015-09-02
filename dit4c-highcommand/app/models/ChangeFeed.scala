package models

import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import providers.db.CouchDB
import com.google.inject.Inject
import play.api.libs.iteratee.Concurrent

class ChangeFeed @Inject() (db: CouchDB.Database) {
  import ChangeFeed._

  val (eventBus, channel) = Concurrent.broadcast[Change]

  {
    import net.liftweb.json._
    implicit val formats = db.asSohvaDb.couch.serializer.formats
    db.asSohvaDb.changes().subscribe {
      case (docId, Some(doc)) =>
        Json.parse(compact(render(doc))) match {
          case obj: JsObject =>
            channel.push(Update(docId, obj))
          case _: JsValue =>
            // It should be an object
        }
      case (docId, None) =>
        channel.push(Deletion(docId))
    }

  }

  def changes: Enumerator[Change] = eventBus

}

object ChangeFeed {

  sealed trait Change
  case class Update(id: String, data: JsObject) extends Change
  case class Deletion(id: String) extends Change

}