package models

import play.api.libs.json._
import play.api.http.Writeable
import play.api.http.ContentTypes
import play.api.Logger
import play.twirl.api.JavaScript
import scala.concurrent.Future
import providers.db.CouchDB
import scala.concurrent.ExecutionContext
import gnieh.sohva.{IdRev, ViewDoc, ViewResult}
import gnieh.sohva.async.{Database => SohvaDatabase}
import net.liftweb.{json => lift}

trait DAOUtils {
  import scala.language.implicitConversions

  import play.api.libs.functional.syntax._
  import play.api.Play.current

  implicit protected def ec: ExecutionContext
  protected def db: CouchDB.Database

  implicit def toSohvaDb(db: CouchDB.Database): SohvaDatabase = db.asSohvaDb
  
  implicit def idRevJsValue[JsValue](json: play.api.libs.json.JsValue) =
    new IdRev {
      val _id = (json \ "_id").as[String]
      withRev((json \ "_rev").asOpt[String])
    }
  
  implicit def idRev[M <: BaseModel](model: M): IdRev =
    new IdRev {
      val _id = model.id
      withRev(model._rev)
    }

  protected def fromJson[A](json: JsValue)(implicit reads: Reads[A]): Option[A] =
    Json.fromJson[A](json)(reads) match {
      case JsSuccess(obj, _) => Some(obj)
      case JsError(messages) =>
        Logger.debug((
            "Errors converting from JSON:" ::
            messages.toList :::
            Json.prettyPrint(json) ::
            Nil).mkString("\n"))
        None
    }

  protected def fromJson[A](json: Seq[JsValue])(implicit reads: Reads[A]): Seq[A] =
    json.flatMap(fromJson[A](_))

  implicit def js2ViewDoc(map: JavaScript): ViewDoc = ViewDoc(map.body, None)

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

  object utils {

    def create[M <: DAOModel[M]](newModelFunc: String => M)(
        implicit rjs: Reads[M], wjs: Writes[M]): Future[M] =
      for {
        id <- db.newID
        newModel = newModelFunc(id)
        model <- update(newModel)
      } yield model

    def get[M <: DAOModel[M]](id: String)(
        implicit wjs: Writes[M], rjs: Reads[M]): Future[Option[M]] =
      for {
        possibleDoc <- db.getDocById[JsValue](id)
      } yield possibleDoc.flatMap(fromJson[M])
      
    def list[M <: DAOModel[M]](typeValue: String)(
        implicit wjs: Writes[M], rjs: Reads[M]): Future[Seq[M]] =
      for {
        result <-
          db.design("main").view("all_by_type")
            .query[String, JsValue, JsValue](
                key=Some(typeValue), include_docs=true)
      } yield fromJson[M](result.rows.flatMap(_.doc))

    def delete[M <: BaseModel](model: M): Future[Unit] = {
      db.deleteDoc(model).map(_ => ())
    }

    def delete(id: String, rev: String): Future[Unit] =
      db.deleteDoc(id -> rev) {
        case (id, rev) => new IdRev {
          val _id = id
          withRev(Some(rev))
        }
      }.map(_ => ())

    def update[M <: DAOModel[M]](changed: => M)(
        implicit rjs: Reads[M], wjs: Writes[M]): Future[M] =
      for {
        response <- db.saveDoc(Json.toJson(changed))
      } yield fromJson[M](response).get

    class UpdateOp[M <: DAOModel[M]](model: M)(
        implicit rjs: Reads[M], wjs: Writes[M]) extends UpdateOperation[M] {
      override def exec() = update(model)

      // Used to avoid updates that don't change anything
      override def execIfDifferent[N >: M](other: N) =
        if (model == other) Future.successful(other) else exec()
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

trait UpdatableModel[U] {
  def update: U
}

trait UpdateOperation[+M] {
  def exec(): Future[M]
  def execIfDifferent[N >: M](other: N): Future[N]
}