package models

import scala.concurrent.Future
import play.api.libs.json._
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import providers.auth.Identity
import java.time.Instant
import akka.stream.scaladsl.Source

class EventDAO @Inject() (protected val db: CouchDB.Database)
    (implicit protected val ec: ExecutionContext)
    extends DAOUtils {
  import play.api.libs.functional.syntax._

  val typeValue = "Event"

  def createLogin(
      user: User,
      identity: Identity,
      timestamp: Instant): Future[Event.Login] =
    utils.create[LoginImpl] { id =>
      LoginImpl(id, None, timestamp, user.id, identity.uniqueId,
          user.name.orElse(identity.name),
          user.email.orElse(identity.emailAddress))
    }

  def listLogins(from: Option[Instant], to: Option[Instant]): Future[Seq[Event.Login]] =
      for {
        result <-
          db.design("main").view("login_events")
            .query[JsValue, JsValue, JsValue](
                startkey=from.map(Json.toJson(_)),
                endkey=to.map(Json.toJson(_)),
                inclusive_end=false, include_docs=true)
      } yield fromJson[LoginImpl](result.rows.flatMap(_.doc))

  def streamLogins(from: Option[Instant]): Future[(Seq[Event.Login],Source[Event.Login, Future[Unit]])]=
      for {
        // Get current state
        result <-
          db.design("main").view("login_events")
            .query[JsValue, JsValue, JsValue](
                startkey=from.map(Json.toJson(_)),
                include_docs=true, update_seq=true)
        objs = fromJson[LoginImpl](result.rows.flatMap(_.doc))
        // Get all changes made after the base query
        changes =
          (new ChangeFeed(db))
              .changes[LoginImpl](result.update_seq, Some("main/login_events"))
              .mapAsync(1) { change =>
            change match {
              case ChangeFeed.Update(id, obj) => Future.successful(Some(obj))
              case ChangeFeed.Deletion(id) => Future.successful(None)
            }
          }.mapConcat {
            case Some(v) => v :: Nil
            case None => Nil
          }
      } yield (objs, changes)

  def get(id: String): Future[Option[Event]] = utils.get[Event](id)

  implicit val loginFormat: Format[LoginImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "timestamp").format[Instant] and
    (__ \ "userID").format[String] and
    (__ \ "identity").format[String] and
    (__ \ "name").formatNullable[String] and
    (__ \ "email").formatNullable[String]
  )(LoginImpl.apply _, unlift(LoginImpl.unapply))
    .withTypeAttribute(typeValue)
    .withFixedProperty("subtype", JsString("Login"))

  implicit val eventReads: Reads[Event] =
    loginFormat.map(_.asInstanceOf[Event])

  case class LoginImpl(
      id: String,
      _rev: Option[String],
      timestamp: Instant,
      userId: String,
      identity: String,
      name: Option[String],
      email: Option[String]) extends Event.Login {

  }

}

trait Event extends BaseModel {
  def timestamp: Instant
}

object Event {

  trait Login extends Event {
    def userId: String
    def identity: String
    def name: Option[String]
    def email: Option[String]
  }

}
