package domain

import akka.actor.ActorRef
import scala.concurrent.duration._
import com.softwaremill.tagging._
import akka.persistence.fsm.PersistentFSM
import java.time.Instant
import scala.reflect.{ClassTag, classTag}
import java.util.Base64
import services.UserSharder
import domain.identity.DomainEvent
import IdentityAggregate.{State, Data}

object IdentityAggregate {

  type Key = String

  sealed trait State extends PersistentFSM.FSMState {
    override def identifier = this.getClass.getSimpleName.stripSuffix("$")
  }
  case object Unassociated extends State
  case object Associated extends State

  sealed trait Data
  case class IdentityData(associatedUserId: Option[UserAggregate.Id] = None) extends Data

  sealed trait Command extends BaseCommand
  case object GetUser extends Command

  sealed trait Response extends BaseResponse
  sealed trait GetUserResponse extends Response
  case class UserFound(userId: UserAggregate.Id) extends GetUserResponse

}

class IdentityAggregate(
    userSharder: ActorRef @@ UserSharder.type)
      extends PersistentFSM[State, Data, DomainEvent] {
  import BaseDomainEvent.now
  import domain.identity._
  import IdentityAggregate._

  val key: IdentityAggregate.Key = new String(Base64.getUrlDecoder().decode(self.path.name), "utf8")
  override lazy val persistenceId: String = "Identity-" + self.path.name

  startWith(Unassociated, IdentityData())

  var notifyWhenAssociated: Seq[ActorRef] = Seq.empty[ActorRef]

  when(Unassociated) {
    case Event(GetUser, _) =>
      notifyWhenAssociated +:= sender
      userSharder ! UserSharder.CreateNewUser
      stay
    case Event(UserAggregate.CreateResponse(userId), _) =>
      goto(Associated) applying(AssociatedUser(userId, now))
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

  def domainEventClassTag: ClassTag[DomainEvent] = classTag[DomainEvent]

}