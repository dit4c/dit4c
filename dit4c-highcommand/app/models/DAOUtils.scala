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
import gnieh.sohva.ViewDoc
import gnieh.sohva.async.{Database => SohvaDatabase}
import net.liftweb.{json => lift}

trait DAOUtils {
  import scala.language.implicitConversions

  import play.api.libs.functional.syntax._
  import play.api.Play.current

  implicit protected def ec: ExecutionContext
  protected def db: CouchDB.Database

  implicit def toSohvaDb(db: CouchDB.Database): SohvaDatabase = db.asSohvaDb
  implicit def idRev[M <: BaseModel](model: M) =
    new gnieh.sohva.IdRev {
      val _id = model.id
      withRev(model._rev)
    }

  implicit def convertJsValue2JValue(v: JsValue): lift.JValue =
    lift.parse(Json.stringify(v))

  implicit def convertJValue2JsValue(v: lift.JValue): JsValue =
    Json.parse(lift.compact(lift.render(v)))

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

  case class TemporaryView(val map: JavaScript) {
    def asViewDoc: ViewDoc = ViewDoc(map.body, None)
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
        possibleRawDoc <- db.getRawDocById(id)
      } yield possibleRawDoc.map(convertJValue2JsValue).flatMap(fromJson[M])

    def delete[M <: BaseModel](model: M): Future[Unit] = {
      db.deleteDoc(model).map( _ => ())
    }

    def delete(id: String, rev: String): Future[Unit] =
      db.deleteDoc(id -> rev) {
        case (id, rev) => new gnieh.sohva.IdRev {
          val _id = id
          withRev(Some(rev))
        }
      }.map( _ => ())

    def update[M <: DAOModel[M]](changed: => M)(
        implicit rjs: Reads[M], wjs: Writes[M]): Future[M] = {
      for {
        response <- db.saveRawDoc(Json.toJson(changed))
      } yield {
        fromJson[M](convertJValue2JsValue(response)).get
      }
    }
    
    def runView[M](tempView: TemporaryView)(
        implicit rjs: Reads[M], wjs: Writes[M]): Future[Seq[M]] =
      for {
        rawViewResult <- db.temporaryView(tempView.asViewDoc).queryRaw()
      } yield {
        rawViewResult.rows
          .map(_.value)
          .map(convertJValue2JsValue)
          .flatMap(fromJson[M])
      }

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