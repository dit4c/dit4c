package dit4c.scheduler.domain

import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import scala.reflect._
import scala.concurrent.duration._
import java.time.Instant
import java.security.interfaces.{RSAPublicKey => JavaRSAPublicKey}
import akka.actor.Props
import akka.actor.ActorRef

object Instance {

  type Id = String
  type ImageName = String
  type ImageId = String

  def props(pId: String): Props = Props(classOf[RktNode], pId)

  sealed trait SourceImage
  case class NamedImage(name: ImageName) extends SourceImage
  case class LocalImage(id: ImageId) extends SourceImage

  sealed trait InstanceSigningKey
  case class RSAPublicKey(key: JavaRSAPublicKey) extends InstanceSigningKey

  /**
   * 128-bit identifier as hexadecimal
   */
  def newId = Seq.fill(16)(Random.nextInt(255)).map(i => f"$i%x").mkString

  trait State extends BasePersistentFSMState
  case object JustCreated extends State
  case object WaitingForImage extends State
  case object Starting extends State
  case object Running extends State
  case object Stopping extends State
  case object Finished extends State
  case object Errored extends State

  sealed trait Data
  case object NoData extends Data
  trait DataWithId extends Data { def id: Id }
  case class SomeData(id: Id, image: SourceImage) extends DataWithId

  trait Command
  case object GetStatus extends Command
  case class Initiate(image: SourceImage) extends Command
  case class ReceiveImage(id: LocalImage) extends Command
  case class AssociateSigningKey(key: InstanceSigningKey) extends Command
  case object ConfirmStart extends Command
  case object Terminate extends Command
  case object ConfirmTerminated extends Command
  case class Error(msg: String) extends Command

  trait DomainEvent extends BaseDomainEvent
  case class Initiated(
      val image: SourceImage,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class FetchedImage(
      val image: LocalImage,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedSigningKey(
      val key: InstanceSigningKey,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedStart(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class RequestedTermination(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class Terminated(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ErrorOccurred(
      val message: String,
      val timestamp: Instant = Instant.now) extends DomainEvent

}

class Instance(val persistenceId: String, worker: ActorRef)
    extends PersistentFSM[Instance.State, Instance.Data, Instance.DomainEvent] {
  import Instance._

  startWith(JustCreated, NoData)

  when(JustCreated) {
    case Event(Initiate(image @ NamedImage(imageName)), _) =>
      // Ask cluster to fetch image for instance
      worker ! InstanceWorker.Fetch(image)
      // Wait for reply
      goto(WaitingForImage).applying(Initiated(image)).andThen {
        case data => sender ! data
      }
  }

  when(WaitingForImage) {
    case Event(ReceiveImage(localImage), data: DataWithId) =>
      // Ask worker to start instance with image
      worker ! InstanceWorker.Start(localImage)
      goto(Starting).applying(FetchedImage(localImage)).andThen {
        case data => sender ! data
      }
  }

  when(Starting) {
    case Event(AssociateSigningKey(key), _) =>
      stay.applying(AssociatedSigningKey(key))
    case Event(ConfirmStart, _) =>
      goto(Starting).applying(ConfirmedStart())
  }

  when(Running) {
    case Event(Terminate, data: DataWithId) =>
      // Ask cluster to stop instance
      worker ! InstanceWorker.Stop
      goto(Stopping).applying(RequestedTermination())
  }

  when(Stopping) {
    case Event(ConfirmTerminated, _) =>
      goto(Finished).applying(Terminated())
  }

  when(Finished, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  when(Errored, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  whenUnhandled {
    case Event(GetStatus, data) =>
      stay replying data
    case Event(Error(msg), _) =>
      goto(Errored).applying(ErrorOccurred(msg))
  }

  def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Instance.Data = {
    domainEvent match {
      case _: DomainEvent => currentData // TODO: Implement data mutation
    }
  }

  def domainEventClassTag: ClassTag[Instance.DomainEvent] =
    classTag[DomainEvent]


}