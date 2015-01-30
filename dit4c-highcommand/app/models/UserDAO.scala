package models

import com.google.inject.Inject
import providers.db.CouchDB
import providers.auth._
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import scala.concurrent.Future
import play.twirl.api.JavaScript

class UserDAO @Inject() (protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.Reads._
  import play.api.Play.current

  def createWith(identity: Identity): Future[User] =
    utils.create { id =>
      UserImpl(id, None,
          identity.name, identity.emailAddress, Seq(identity.uniqueId))
    }

  def findWith(identity: Identity): Future[Option[User]] =
    for {
      users <-
        db.design("main").view("user_identities")
          .query[String, JsValue, JsValue](
              key=Some(identity.uniqueId), include_docs=true)
          .map(result => fromJson[UserImpl](result.rows.flatMap(_.doc)))
    } yield users.headOption

  def get(id: String): Future[Option[User]] = utils.get(id)

  def createMergeOrUpdate(
      currentUser: Option[User],
      loginIdentity: Identity): Future[User] =
    findWith(loginIdentity).map((currentUser, _)).flatMap {
      case (Some(user1), Some(user2)) if user1 != user2 =>
        // TODO: Merge users
        // This is likely to be quite tricky, as user2 might own compute nodes
        // and containers.
        ???
      case (Some(user), _) =>
        updateWithIdentity(user, loginIdentity)
      case (None, Some(user)) =>
        updateWithIdentity(user, loginIdentity)
      case (None, None) =>
        createWith(loginIdentity)
    }

  def updateWithIdentity(user: User, identity: Identity) =
    user.update
      .withName(user.name.orElse(identity.name))
      .withEmail(user.email.orElse(identity.emailAddress))
      .withIdentities((user.identities :+ identity.uniqueId).distinct)
      .execIfDifferent(user)

  implicit val userFormat: Format[UserImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").formatNullable[String] and
    (__ \ "email").formatNullable[String] and
    (__ \ "identities").format[Seq[String]]
  )(UserImpl.apply _, unlift(UserImpl.unapply))
    .withTypeAttribute("User")

  implicit class ReadCombiner[A](r1: Reads[A]) {
    def or(r2: Reads[A]) = new Reads[A] {
      override def reads(json: JsValue) =
        r1.reads(json) match {
          case result: JsSuccess[A] => result
          case _: JsError => r2.reads(json)
        }
    }
  }

  case class UserImpl(
      val id: String,
      val _rev: Option[String],
      val name: Option[String],
      val email: Option[String],
      val identities: Seq[String]
      )(implicit ec: ExecutionContext)
      extends User
      with DAOModel[UserImpl]
      with UpdatableModel[User.UpdateOp] {
    import scala.language.implicitConversions

    override def revUpdate(rev: String) = this.copy(_rev = Some(rev))

    override def update = updateOp(this)
    override def delete = utils.delete(this)

    // Used to update multiple attributes at once
    implicit def updateOp(model: UserImpl): User.UpdateOp =
      new utils.UpdateOp(model) with User.UpdateOp {
        override def withName(name: Option[String]) =
          model.copy(name = name)

        override def withEmail(email: Option[String]) =
          model.copy(email = email)

        override def withIdentities(identities: Seq[String]) =
          model.copy(identities = identities)
      }

  }

}

trait User extends BaseModel {
  def name: Option[String]
  def email: Option[String]
  def identities: Seq[String]

  def update: User.UpdateOp
  def delete: Future[Unit]
}

object User {
  trait UpdateOp extends UpdateOperation[User] {
    def withName(name: Option[String]): UpdateOp
    def withEmail(email: Option[String]): UpdateOp
    def withIdentities(identities: Seq[String]): UpdateOp
  }
}
