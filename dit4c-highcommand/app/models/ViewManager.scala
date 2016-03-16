package models

import scala.concurrent.{ExecutionContext,Future}
import gnieh.sohva.ViewDoc
import gnieh.sohva.async.Design
import scala.annotation.implicitNotFound

object ViewManager {

  val views: Map[String, ViewDoc] = {
    import _root_.views.js.models.viewdocs._
    Map(
      "access_tokens" -> ViewDoc(access_tokens().body, None),
      "all_by_type" -> ViewDoc(all_by_type().body, None),
      "user_identities" -> ViewDoc(user_identities().body, None)
    )
  }
  
  val filters: Map[String, String] = {
    import _root_.views.js.models.filters._
    Map(
      "type/Event" -> type_or_deletion("Event").body
    )
  }

  def update(design: Design)(implicit ec: ExecutionContext) =
    for {
      designDoc <- design.getDesignDocument.flatMap {
        case Some(doc) => Future.successful(doc)
        case None => design.create
      }
      // Copy in view definitions
      updatedDoc = designDoc.copy(filters = filters, views = views)
                            .withRev(designDoc._rev)
      // Update view only if it will change
      _ <-
        if (updatedDoc == designDoc) Future.successful(())
        else design.db.saveDoc(updatedDoc)
    } yield design

}