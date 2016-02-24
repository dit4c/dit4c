package models

import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import providers.db.CouchDB
import scala.concurrent.Future
import play.api.libs.json._
import play.api.mvc.Results.EmptyContent
import scala.util.Try
import java.security.interfaces.RSAPrivateKey
import java.util.Date
import java.util.TimeZone
import com.nimbusds.jose.jwk.RSAKey
import java.security.Signature
import com.nimbusds.jose.util.Base64
import play.api.libs.ws.InMemoryBody
import java.security.MessageDigest
import providers.RoutingMapEmitter
import providers.machineshop.ContainerProvider
import providers.machineshop.MachineShop
import gnieh.sohva.async.View
import providers.RoutingMapEmitter

class ComputeNodeDAO @Inject() (
    protected val db: CouchDB.Database,
    protected val keyDao: KeyDAO
    )(implicit protected val ec: ExecutionContext)
  extends DAOUtils {
  import play.api.libs.functional.syntax._
  import play.api.Play.current

  val typeValue = "ComputeNode"

  def create(
      user: User,
      name: String,
      serverId: String,
      managementUrl: String,
      backend: RoutingMapEmitter.Backend): Future[ComputeNode] =
    utils.create { id =>
      ComputeNodeImpl(id, None,
          name, serverId, managementUrl, backend, Set(user.id), Set(user.id))
    }

  def list: Future[Seq[ComputeNode]] = utils.list[ComputeNodeImpl](typeValue)

  def get(id: String) = utils.get(id)

  implicit val computeNodeFormat: Format[ComputeNodeImpl] = (
    (__ \ "_id").format[String] and
    (__ \ "_rev").formatNullable[String] and
    (__ \ "name").format[String] and
    (__ \ "serverID").format[String] and
    (__ \ "managementURL").format[String] and
    (__ \ "backend").format[RoutingMapEmitter.Backend] and
    (__ \ "ownerIDs").format[Set[String]] and
    (__ \ "userIDs").format[Set[String]]
  )(ComputeNodeImpl.apply _, unlift(ComputeNodeImpl.unapply))
    .withTypeAttribute(typeValue)

  def fromJson(json: JsValue) = json.asOpt[ComputeNodeImpl]

  case class ComputeNodeImpl(
      id: String,
      _rev: Option[String],
      name: String,
      serverId: String,
      managementUrl: String,
      backend: RoutingMapEmitter.Backend,
      ownerIDs: Set[String],
      userIDs: Set[String]
      )(implicit ec: ExecutionContext)
      extends ComputeNode
      with DAOModel[ComputeNodeImpl]
      with UpdatableModel[ComputeNode.UpdateOp] {
    import scala.language.implicitConversions

    import play.api.Play.current

    val containers = new ContainerProvider(
      managementUrl,
      () => keyDao.bestSigningKey.map(_.get.toJWK))

    override def update = updateOp(this)

    override def addOwner(user: User) =
      utils.update(this.copy(
          ownerIDs = ownerIDs + user.id,
          userIDs = userIDs + user.id))

    override def addUser(user: User) =
      utils.update(this.copy(userIDs = userIDs + user.id))

    override def removeOwner(userId: String) =
      utils.update(this.copy(ownerIDs = userIDs - userId))

    override def removeUser(userId: String) =
      utils.update(this.copy(userIDs = userIDs - userId))

    override def delete: Future[Unit] = utils.delete(this)

    override def revUpdate(newRev: String) = this.copy(_rev = Some(newRev))

    // Used to update multiple attributes at once
    implicit def updateOp(model: ComputeNodeImpl): ComputeNode.UpdateOp =
      new utils.UpdateOp(model) with ComputeNode.UpdateOp {
        override def withName(name: String) =
          model.copy(name = name)

        override def withManagementUrl(url: String) =
          model.copy(managementUrl = url)

        override def withBackend(backend: RoutingMapEmitter.Backend) =
          model.copy(backend = backend)

        override def withOwners(ids: Set[String]) =
          model.copy(ownerIDs = ids)

        override def withUsers(ids: Set[String]) =
          model.copy(userIDs = ids)
      }

  }

}

trait ComputeNode extends OwnableModel with UsableModel {
  def name: String
  def serverId: String
  def managementUrl: String
  def backend: RoutingMapEmitter.Backend

  def containers: ContainerProvider

  def update: ComputeNode.UpdateOp

  def addOwner(user: User): Future[ComputeNode]
  def addUser(user: User): Future[ComputeNode]
  def removeOwner(userId: String): Future[ComputeNode]
  def removeUser(userId: String): Future[ComputeNode]

  def delete: Future[Unit]

}

object ComputeNode {
  trait UpdateOp extends UpdateOperation[ComputeNode] {
    def withName(name: String): UpdateOp
    def withManagementUrl(url: String): UpdateOp
    def withBackend(backend: RoutingMapEmitter.Backend): UpdateOp
    def withOwners(ids: Set[String]): UpdateOp
    def withUsers(ids: Set[String]): UpdateOp
  }
}



