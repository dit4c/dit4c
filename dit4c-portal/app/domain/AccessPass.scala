package domain

import com.softwaremill.tagging._
import akka.actor._
import akka.persistence.PersistentActor
import akka.util.ByteString
import com.google.protobuf.{ByteString => PBByteString}
import utils.akka.ActorHelpers
import dit4c.protobuf.tokens.ClusterAccessPass
import java.time.Instant
import domain.accesspass.PassVerificationWorker
import dit4c.common.KeyHelpers.PGPFingerprint

object AccessPass {

  def props(scheduler: ActorRef @@ SchedulerAggregate): Props =
    Props(classOf[AccessPass], scheduler)

  sealed trait Command extends BaseCommand
  case class Register(signedData: ByteString) extends Command
  case object GetDecodedPass extends Command
  case object GetSignedPass extends Command

  sealed trait Response extends BaseResponse
  trait RegisterResponse extends Response
  case class RegistrationSucceeded(id: String) extends RegisterResponse
  case class RegistrationFailed(reason: String) extends RegisterResponse
  trait GetDecodedPassResponse extends Response
  case class ValidPass(
      pass: ClusterAccessPass,
      expires: Option[Instant],
      signedBy: PGPFingerprint) extends GetDecodedPassResponse
  case class ExpiredPass(
      pass: ClusterAccessPass) extends GetDecodedPassResponse
  case class UnverifiablePass(
      reason: String) extends GetDecodedPassResponse
  trait GetSignedPassResponse extends Response
  case class SignedPass(
      signedData: ByteString) extends GetSignedPassResponse

  def calculateId(signedData: ByteString) = {
    import dit4c.common.KeyHelpers._
    signedData.digest("SHA-512").map(b => f"$b%02x").mkString
  }

}

class AccessPass(scheduler: ActorRef @@ SchedulerAggregate) extends PersistentActor
    with ActorLogging with ActorHelpers {
  import AccessPass._
  import BaseDomainEvent.now
  import domain.scheduler.accesspass._
  import scala.language.implicitConversions

  lazy val accessPassId = self.path.name
  override lazy val persistenceId: String = "AccessPass-" + self.path.name

  override val receiveCommand = doesNotExist

  def doesNotExist = sealedReceive[Command] {
    case Register(signedData) if !matchForId(signedData) =>
      sender ! RegistrationFailed("ID does not match data")
    case Register(signedData) =>
      val worker = context.actorOf(
            Props(classOf[PassVerificationWorker],
                scheduler, signedData),
            "registration-verification-worker")
      context.become(pendingVerificationCheck(sender, signedData))
  }

  def pendingVerificationCheck(
      dataProvider: ActorRef,
      signedData: ByteString): Receive = {
    case cmd: Command => stash
    case r: PassVerificationWorker.Result => r match {
      case _: PassVerificationWorker.ValidPass =>
        persist(Registered(signedData, now))(updateState _)
        dataProvider ! RegistrationSucceeded(accessPassId)
        unstashAll
        context.become(active(signedData))
      case _: PassVerificationWorker.ExpiredPass =>
        dataProvider ! RegistrationFailed("Pass is expired/invalid")
        unstashAll
        context.become(doesNotExist)
      case PassVerificationWorker.UnverifiablePass(reason) =>
        dataProvider ! RegistrationFailed(reason)
        unstashAll
        context.become(doesNotExist)
    }
  }

  def active(
      signedData: ByteString,
      pendingDecode: List[ActorRef] = Nil): Receive = {
    sealedReceive[Command] {
      case _: Register =>
        sender ! RegistrationFailed("Have data already")
      case GetDecodedPass =>
        context.actorOf(
            Props(classOf[PassVerificationWorker],
                scheduler, signedData))
        context.become(active(signedData, pendingDecode :+ sender))
      case GetSignedPass =>
        sender ! SignedPass(signedData)
    } orElse
    sealedReceive[PassVerificationWorker.Result] {
      case PassVerificationWorker.ValidPass(cap, expiry, signedBy) =>
        pendingDecode.foreach { waitingRef =>
          waitingRef ! ValidPass(cap, expiry, signedBy)
        }
        context.become(active(signedData))
      case PassVerificationWorker.ExpiredPass(cap) =>
        pendingDecode.foreach { waitingRef =>
          waitingRef ! ExpiredPass(cap)
        }
        context.become(active(signedData))
      case PassVerificationWorker.UnverifiablePass(reason) =>
        pendingDecode.foreach { waitingRef =>
          waitingRef ! UnverifiablePass(reason)
        }
        context.become(active(signedData))
    }
  }

  override val receiveRecover = sealedReceive[DomainEvent](updateState _)

  protected def updateState(evt: DomainEvent): Unit = evt match {
    case Registered(signedData, _) => context.become(active(signedData))
  }

  private def matchForId(signedData: ByteString): Boolean =
    AccessPass.calculateId(signedData) == accessPassId

  implicit private def pbByteString2ByteString(pbbs: PBByteString): ByteString =
    ByteString(pbbs.toByteArray)

  implicit private def byteString2PbByteString(bs: ByteString): PBByteString =
    PBByteString.copyFrom(bs.toArray)

}