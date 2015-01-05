package models

import scala.concurrent.{ExecutionContext,Future}
import gnieh.sohva.ViewDoc
import gnieh.sohva.async.Design
import scala.annotation.implicitNotFound

object ViewManager {

  val designViews: Map[String, ViewDoc] = {
    import views.js.models._
    Map(
      "access_tokens" -> ViewDoc(access_tokens().body, None),
      "all_by_type" -> ViewDoc(all_by_type().body, None),
      "user_identities" -> ViewDoc(user_identities().body, None)
    )
  }

  def update(design: Design)(implicit ec: ExecutionContext) =
    for {
      designDoc <- design.getDesignDocument.flatMap {
        case Some(doc) => Future.successful(doc)
        case None => design.create
      }
      // Copy in view definitions
      updatedDoc = designDoc.copy(views = designViews).withRev(designDoc._rev)
      // Update view only if it will change
      _ <-
        if (updatedDoc == designDoc) Future.successful(())
        else design.db.saveDoc(updatedDoc)
    } yield design

}