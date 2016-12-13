package domain

import akka.persistence.PersistentActor
import akka.actor.ActorLogging
import utils.akka.ActorHelpers
import domain.SchedulerAggregate.GetKeysResponse

object KeyRingAggregate {
  
  type KeyBlock = String
  
  trait Command extends BaseCommand
  case class ReceiveKeySubmission(pgpPublicKeyBlock: KeyBlock) extends Command
  case object GetKeys extends Command
  
  trait Response extends BaseResponse
  trait ReceiveKeySubmissionResponse extends Response
  case class KeySubmissionAccepted(currentState: String) extends Response
  case class KeySubmissionRejected(reason: String) extends Response
  trait GetKeysResponse extends Response
  case object NoKeysAvailable extends GetKeysResponse
  case class CurrentKeyBlock(pgpPublicKeyBlock: KeyBlock) extends GetKeysResponse
  
}


class KeyRingAggregate extends PersistentActor
    with ActorLogging with ActorHelpers {
  import KeyRingAggregate._
  import domain.keyring._
  import BaseDomainEvent.now
  import dit4c.common.KeyHelpers._
  import scala.language.implicitConversions

  lazy val primaryKeyId = self.path.name
  override lazy val persistenceId: String = "KeyRing-" + self.path.name
  
  var currentKeyBlock: Option[String] = None

  override val receiveCommand =  sealedReceive[Command] {
    case GetKeys =>
      sender ! currentKeyBlock.map(CurrentKeyBlock(_)).getOrElse(NoKeysAvailable)
    case ReceiveKeySubmission(pgpPublicKeyBlock) =>
      combineWithCurrent(pgpPublicKeyBlock) match {
        case Left(reason) =>
          sender ! KeySubmissionRejected(reason)
        case Right(updatedKeyBlock) =>
          persist(AcceptedKeyBlockSubmission(pgpPublicKeyBlock, now))(updateState _)
          sender ! KeySubmissionAccepted(currentKeyBlock.get)
      }
  }

  override val receiveRecover = sealedReceive[DomainEvent](updateState _)

  protected def updateState(evt: DomainEvent): Unit = evt match {
    case AcceptedKeyBlockSubmission(keyBlock, _) =>
      // Update key block, skipping blocks that merge with errors
      combineWithCurrent(keyBlock).right.foreach { kb =>
        currentKeyBlock = Some(kb)
      }
  }
  
  protected def combineWithCurrent(newKeyBlock: String): Either[String, KeyBlock] =
    parseArmoredPublicKeyRing(newKeyBlock) match {
      case Left(reason) =>
        Left(reason)
      case Right(pkr) if pkr.getPublicKey.fingerprint.string != primaryKeyId =>
        Left("Primary key fingerprint does not match persistent entity ID")
      case Right(pkr) =>
        // Obviously this needs improvement!
        // TODO: do proper merge
        Right(newKeyBlock)
    }

}