package services

import akka.actor.ActorRef
import akka.util.Timeout
import com.softwaremill.tagging._
import com.mohiva.play.silhouette.api.services.{IdentityService => SilhouetteIdentityService}
import com.mohiva.play.silhouette.api.{Identity => SilhouetteIdentity}
import com.mohiva.play.silhouette.api.LoginInfo
import scala.concurrent.Future
import scala.concurrent.duration._
import domain.IdentityAggregate
import scala.concurrent.ExecutionContext

object IdentityService {
  case class User(id: String, loginInfo: LoginInfo) extends SilhouetteIdentity

  def toIdentityKey(loginInfo: LoginInfo): IdentityAggregate.Key = loginInfo match {
    case LoginInfo(providerId, providerKey) => providerId + ":" + providerKey
  }

}

class IdentityService(
    val identityAggregateManager: ActorRef @@ IdentityAggregateManager)(implicit ec: ExecutionContext)
    extends SilhouetteIdentityService[IdentityService.User] {
  import IdentityService._
  import akka.pattern.ask

  implicit val timeout = Timeout(1.minute)

  def retrieve(loginInfo: LoginInfo): Future[Option[User]] =
    (identityAggregateManager ? IdentityAggregateManager.IdentityEnvelope(
        toIdentityKey(loginInfo), IdentityAggregate.GetUser)).collect {
    case IdentityAggregate.UserFound(id) => Some(User(id, loginInfo))
  }
}