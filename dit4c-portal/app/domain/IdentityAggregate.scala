package domain

import akka.actor.ActorRef
import scala.concurrent.duration._
import com.softwaremill.tagging._
import akka.persistence.fsm.PersistentFSM
import services.UserAggregateManager
import java.time.Instant
import scala.reflect.{ClassTag, classTag}

object IdentityAggregate {

  type Key = String

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Unassociated extends State
  case object Associated extends State

  sealed trait Data
  case class IdentityData(associatedUserId: Option[UserAggregate.Id] = None) extends Data

  sealed trait Command
  case object GetUser extends Command

  sealed trait Response
  sealed trait GetUserResponse extends Response
  case class UserFound(userId: UserAggregate.Id) extends GetUserResponse

  sealed trait DomainEvent extends BaseDomainEvent
  case class AssociatedUser(userId: String, timestamp: Instant = Instant.now) extends DomainEvent

}

class IdentityAggregate(
    key: IdentityAggregate.Key,
    userAggregateManager: ActorRef @@ UserAggregateManager)
      extends PersistentFSM[IdentityAggregate.State, IdentityAggregate.Data, IdentityAggregate.DomainEvent] {
  import IdentityAggregate._

  startWith(Unassociated, IdentityData())

  var notifyWhenAssociated: Seq[ActorRef] = Seq.empty[ActorRef]

  when(Unassociated) {
    case Event(GetUser, _) =>
      notifyWhenAssociated +:= sender
      userAggregateManager ! UserAggregateManager.CreateNewUser
      stay
    case Event(UserAggregateManager.CreatedUser(userId), _) =>
      goto(Associated) applying(AssociatedUser(userId))
  }

  onTransition {
    case Unassociated -> Associated =>
      // Re-send command now that a reply can be sent
      notifyWhenAssociated.foreach { self.tell(GetUser, _) }
      // Clear queue because we no longer need it
      notifyWhenAssociated = Seq.empty
  }

  when(Associated) {
    case Event(GetUser, IdentityData(Some(userId))) =>
      stay replying UserFound(userId)
  }

  override def applyEvent(domainEvent: DomainEvent, currentData: Data) = (domainEvent, currentData) match {
    case (AssociatedUser(userId, _), data: IdentityData) =>
      data.copy(associatedUserId = Some(userId))
  }

  override def persistenceId = self.path.name

  def domainEventClassTag: ClassTag[DomainEvent] = classTag[DomainEvent]

}