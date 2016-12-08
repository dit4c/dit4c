package domain

import com.softwaremill.tagging._
import akka.actor._
import akka.persistence.PersistentActor
import akka.util.ByteString
import com.google.protobuf.{ByteString => PBByteString}
import utils.akka.ActorHelpers

object AccessPass {

  sealed trait Command extends BaseCommand
  case class Register(signedData: ByteString) extends Command


  trait Response extends BaseResponse
  trait RegisterResponse extends Response
  case object RegistrationSucceeded extends RegisterResponse
  case class RegistrationFailed(reason: String) extends RegisterResponse




}

class AccessPass(scheduler: ActorRef @@ SchedulerAggregate) extends PersistentActor
    with ActorLogging with ActorHelpers {
  import AccessPass._
  import BaseDomainEvent.now
  import domain.scheduler.accesspass._
  import scala.language.implicitConversions

  lazy val accessPassId = self.path.name
  override lazy val persistenceId: String = "AccessPass-" + self.path.name

  override val receiveCommand = sealedReceive[Command] {
    case Register(signedData) if !matchForId(signedData) =>
      sender ! RegistrationFailed("ID does not match data")
    case Register(signedData) =>
      persist(Registered(signedData, now))(updateState _)
  }

  def active(signedData: ByteString) = sealedReceive[Command] {
    case r: Register =>
      sender ! RegistrationFailed("Have data already")
  }

  override val receiveRecover = sealedReceive[DomainEvent](updateState _)

  protected def updateState(evt: DomainEvent): Unit = evt match {
    case Registered(signedData, _) => context.become(active(signedData))
  }

  private def matchForId(signedData: ByteString): Boolean =
    AccessPassManager.accessPassId(signedData) == accessPassId

  implicit private def pbByteString2ByteString(pbbs: PBByteString): ByteString =
    ByteString(pbbs.toByteArray)

  implicit private def byteString2PbByteString(bs: ByteString): PBByteString =
    PBByteString.copyFrom(bs.toArray)

}