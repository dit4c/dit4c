package models

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import play.api.libs.json._
import gnieh.sohva.async.View

class ContainerDAO(protected val db: CouchDB.Database)
  (implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  val typeValue = "Container"
  
  def create(
      user: User,
      name: String,
      image: String,
      computeNode: ComputeNode): Future[Container] =
    list.flatMap { containers =>
      utils.create { id =>
        ContainerImpl(id, None, name, image, computeNode.id,
          Set(user.id))
      }
    }

  def get(id: String): Future[Option[Container]] = utils.get(id)

  def list: Future[Seq[Container]] = utils.list[ContainerImpl](typeValue)

  def listFor(node: ComputeNode): Future[Seq[Container]] =
    for {
      containers <- list
    } yield containers.filter(_.computeNodeId == node.id)

  implicit val containerFormat: Format[ContainerImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "image").format[String] and
    (__ \ "computeNodeId").format[String] and
    (__ \ "ownerIDs").format[Set[String]]
  )(ContainerImpl.apply _, unlift(ContainerImpl.unapply))
    .withTypeAttribute(typeValue)


  case class ContainerImpl(
      id: String,
      _rev: Option[String],
      name: String,
      image: String,
      computeNodeId: String,
      ownerIDs: Set[String])
      extends Container
      with DAOModel[ContainerImpl]
      with UpdatableModel[Container.UpdateOp] {
    import scala.language.implicitConversions

    override def delete: Future[Unit] = utils.delete(id, _rev.get)

    override def revUpdate(newRev: String) = this.copy(_rev = Some(newRev))

    override def update = updateOp(this)

    // Used to update multiple attributes at once
    implicit def updateOp(model: ContainerImpl): Container.UpdateOp =
      new utils.UpdateOp(model) with Container.UpdateOp {
        override def withName(name: String) =
          model.copy(name = name)
        override def withOwners(ids: Set[String]) =
          model.copy(ownerIDs = ids)
      }

  }

}

trait Container extends OwnableModel {

  def name: String
  def image: String
  def computeNodeId: String

  def update: Container.UpdateOp
  def delete: Future[Unit]

}

object Container {
  trait UpdateOp extends UpdateOperation[Container] {
    def withName(name: String): UpdateOp
    def withOwners(ids: Set[String]): UpdateOp
  }
}