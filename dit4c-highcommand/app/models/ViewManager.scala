package models

import scala.concurrent.{ExecutionContext,Future}
import gnieh.sohva.ViewDoc
import gnieh.sohva.async.Design
import scala.annotation.implicitNotFound
import play.twirl.api.JavaScript

object ViewManager {

  val views: Iterable[View] = {
    import _root_.views.js.models.viewdocs._
    View("access_tokens", access_tokens()) ::
    View("all_by_type", all_by_type()) ::
    View("user_identities", user_identities()) ::
    View("containers_by_compute_node",
        fixed_property_map(Seq(("type","Container")), Seq("computeNodeId"))) ::
    View("login_events",
        fixed_property_map(
            ("type", "Event") :: ("subtype", "Login") :: Nil,
            "timestamp" :: Nil)) ::
    Nil
  }

  val filters: Iterable[Filter] = {
    import _root_.views.js.models.filters._
    Filter("login_events", fixed_property_filter(
        ("type","Event") :: ("subtype","Login") :: Nil)) ::
    Nil
  }

  def update(design: Design)(implicit ec: ExecutionContext) =
    for {
      designDoc <- design.getDesignDocument.flatMap {
        case Some(doc) => Future.successful(doc)
        case None => design.create
      }
      // Copy in view definitions
      updatedDoc = designDoc
        .copy(filters = filters2Map(filters), views = views2Map(views))
        .withRev(designDoc._rev)
      // Update view only if it will change
      _ <-
        if (updatedDoc == designDoc) Future.successful(())
        else design.db.saveDoc(updatedDoc)
    } yield design

  case class View(name: String, mapF: JavaScript, reduceF: Option[JavaScript] = None)
  case class Filter(name: String, filterF: JavaScript)

  private def views2Map(views: Iterable[View]): Map[String, ViewDoc] =
    views.map { view =>
      (view.name, ViewDoc(view.mapF.body, view.reduceF.map(_.body)))
    }.toMap

  private def filters2Map(filters: Iterable[Filter]): Map[String, String] =
    filters.map { filter =>
      (filter.name, filter.filterF.body)
    }.toMap

}