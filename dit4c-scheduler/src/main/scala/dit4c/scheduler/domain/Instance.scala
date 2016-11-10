package dit4c.scheduler.domain

import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import scala.reflect._
import scala.concurrent.duration._
import java.time.Instant
import java.security.interfaces.{RSAPublicKey => JavaRSAPublicKey}
import akka.actor.Props
import akka.actor.ActorRef
import org.bouncycastle.openpgp.PGPPublicKey

object Instance {

  type Id = String
  type ImageName = String
  type ImageId = String

  def props(worker: ActorRef): Props =
    Props(classOf[Instance], worker)

  sealed trait SourceImage
  case class NamedImage(name: ImageName) extends SourceImage
  case class LocalImage(id: ImageId) extends SourceImage

  case class InstanceSigningKey(key: PGPPublicKey)

  sealed trait State extends BasePersistentFSMState
  case object JustCreated extends State
  case object WaitingForImage extends State
  case object Starting extends State
  case object Running extends State
  case object Stopping extends State
  case object Exited extends State
  case object Saving extends State
  case object Saved extends State
  case object Uploading extends State
  case object Uploaded extends State
  case object Discarding extends State
  case object Discarded extends State
  case object Errored extends State

  sealed trait Data
  case object NoData extends Data
  sealed trait SomeData extends Data {
    def instanceId: String
  }
  case class StartData(
      instanceId: String,
      providedImage: SourceImage,
      resolvedImage: Option[LocalImage],
      portalUri: String,
      signingKey: Option[InstanceSigningKey]) extends SomeData
  case class SaveData(
      instanceId: String,
      signingKey: InstanceSigningKey,
      uploadHelperImage: NamedImage,
      imageServer: String,
      portalUri: String) extends SomeData
  case class DiscardData(instanceId: String) extends SomeData
  case class ErrorData(instanceId: String, errors: List[String]) extends SomeData

  trait Command
  case object GetStatus extends Command
  case class Initiate(
      instanceId: String,
      image: SourceImage,
      portalUri: String) extends Command
  case class ReceiveImage(id: LocalImage) extends Command
  case class AssociateSigningKey(key: InstanceSigningKey) extends Command
  case object ConfirmStart extends Command
  case object ConfirmExited extends Command
  case object Discard extends Command
  case object ConfirmDiscard extends Command
  case class Save(
      helperImage: Instance.NamedImage,
      imageServer: String) extends Command
  protected case object ContinueSave extends Command
  protected case object ContinueDiscard extends Command
  protected case object Upload extends Command
  case object ConfirmSaved extends Command
  case object ConfirmUpload extends Command
  case class Error(msg: String) extends Command

  trait Response
  case class StatusReport(
      state: Instance.State,
      data: Instance.Data)
  case object Ack

  sealed trait DomainEvent extends BaseDomainEvent
  case class Initiated(
      val instanceId: String,
      val image: SourceImage,
      val portalUri: String,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class FetchedImage(
      val image: LocalImage,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class AssociatedSigningKey(
      val key: String,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedStart(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class RequestedSave(
      val helperImage: NamedImage,
      val imageServer: String,
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class RequestedDiscard(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class CommencedUpload(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedExit(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedDiscard(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedSave(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ConfirmedUpload(
      val timestamp: Instant = Instant.now) extends DomainEvent
  case class ErrorOccurred(
      val message: String,
      val timestamp: Instant = Instant.now) extends DomainEvent

}

class Instance(worker: ActorRef)
    extends PersistentFSM[Instance.State, Instance.Data, Instance.DomainEvent] {
  import Instance._
  import dit4c.common.KeyHelpers._

  lazy val persistenceId = self.path.name

  startWith(JustCreated, NoData)

  when(JustCreated) {
    case Event(Initiate(id, image @ NamedImage(imageName), callback), _) =>
      val domainEvent = Initiated(id, image, callback)
      val requester = sender
      goto(WaitingForImage).applying(domainEvent).andThen {
        case data =>
          log.info(s"Fetching image: $image")
          worker ! InstanceWorker.Fetch(image)
          requester ! Ack
      }
  }

  when(WaitingForImage) {
    case Event(ReceiveImage(localImage), StartData(id, _, _, callback, _)) =>
      goto(Starting).applying(FetchedImage(localImage)).andThen {
        case data =>
          log.info(s"Starting with: $localImage")
          worker ! InstanceWorker.Start(id, localImage, callback)
      }
  }

  when(Starting) {
    case Event(AssociateSigningKey(isk), _) =>
      log.info(s"Received signing key: $isk")
      val keyData = new String(isk.key.armoured)
      stay.applying(AssociatedSigningKey(keyData)).andThen(emitStatusReportToEventStream(stateName))
    case Event(ConfirmStart, _) =>
      log.info(s"Confirmed start")
      goto(Running).applying(ConfirmedStart())
  }

  when(Running) {
    case Event(Save(helperImage, imageServer), StartData(id, _, _, portalUri, _) ) =>
      val requester = sender
      goto(Stopping).applying(RequestedSave(helperImage, imageServer)).andThen { _ =>
        worker ! InstanceWorker.Stop(id)
        requester ! Ack
      }
    case Event(Discard, StartData(id, _, _, _, _)) =>
      val requester = sender
      goto(Stopping).applying(RequestedDiscard()).andThen { _ =>
        worker ! InstanceWorker.Stop(id)
        requester ! Ack
      }
  }

  when(Stopping) {
    case Event(ConfirmExited, _) =>
      goto(Exited).applying(ConfirmedExit())
  }

  when(Exited) {
    case Event(Save(helperImage, imageServer), StartData(id, _, _, portalUri, _) ) =>
      val requester = sender
      goto(Saving).applying(RequestedSave(helperImage, imageServer)).andThen { _ =>
        worker ! InstanceWorker.Save(id)
        requester ! Ack
      }
    case Event(Discard, _) =>
      val requester = sender
      goto(Discarding).applying(RequestedDiscard()).andThen { _ =>
        worker ! InstanceWorker.Discard
        requester ! Ack
      }
    case Event(ContinueSave, SaveData(id, _, _, _, _) ) =>
      worker ! InstanceWorker.Save(id)
      goto(Saving)
    case Event(ContinueDiscard, DiscardData(id)) =>
      worker ! InstanceWorker.Discard(id)
      goto(Discarding)

  }

  when(Saving) {
    case Event(ConfirmSaved, _) =>
      goto(Saved).applying(ConfirmedSave())
  }

  when(Saved) {
    case Event(Upload, SaveData(id, _, helperImage, imageServer, portalUri)) =>
      val requester = sender
      goto(Uploading).applying(CommencedUpload()).andThen { _ =>
        worker ! InstanceWorker.Upload(id, helperImage, imageServer, portalUri)
      }
  }

  when(Uploading) {
    case Event(ConfirmUpload, _) =>
      goto(Uploaded).applying(ConfirmedUpload())
  }

  when(Uploaded, stateTimeout = 1.minute) {
    case Event(ConfirmUpload, _) =>
      stay // Idempotent
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  when(Discarding) {
    case Event(ConfirmDiscard, _) =>
      goto(Discarded).applying(ConfirmedDiscard())
  }

  when(Discarded, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  when(Errored, stateTimeout = 1.minute) {
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  whenUnhandled {
    case Event(GetStatus, data) =>
      stay replying StatusReport(stateName, data)
    case Event(Error(msg), _) =>
      goto(Errored).applying(ErrorOccurred(msg))
  }

  onTransition {
    case (from, to) =>
      Option(stateData).collect {
        case data: SomeData => log.info(s"Instance ${data.instanceId}: $from → $to")
      }
      emitStatusReportToEventStream(to)(stateData)
  }

  onTransition {
    case (_, Exited) if stateData.isInstanceOf[SaveData] =>
      log.info("continuing save")
      self ! ContinueSave
    case (_, Exited) if stateData.isInstanceOf[DiscardData] =>
      log.info("continuing discard")
      self ! ContinueDiscard
    case (_, Saved) => self ! Upload
  }

  def applyEvent(
      domainEvent: DomainEvent,
      currentData: Data): Instance.Data = {
    def unhandled(reason: String = "unanticipated") = {
      val msg = s"Unhandled event/state: ($domainEvent, $currentData) → $reason"
      log.error(msg)
      stop(PersistentFSM.Failure(msg))
      currentData
    }
    domainEvent match {
      case Initiated(id, image, portalUri, _) => currentData match {
        case NoData => StartData(id, image, None, portalUri, None)
        case other => unhandled("Cannot initiate twice")
      }
      case FetchedImage(image, _) => currentData match {
        case data: StartData => data.copy(resolvedImage = Some(image))
        case other => unhandled()
      }
      case AssociatedSigningKey(key, _) => currentData match {
        case data: StartData => data.copy(signingKey = Some(InstanceSigningKey(parseArmoredPublicKey(key).right.get)))
        case other => unhandled()
      }
      case RequestedSave(helperImage, imageServer, _) => currentData match {
        case StartData(id, _, _, portalUri, Some(signingKey)) =>
          SaveData(id, signingKey, helperImage, imageServer, portalUri)
        case data: SaveData =>
          // TODO: Get rid of this once we know how it happens
          log.warning("Two requested saves really shouldn't happen!")
          data.copy(uploadHelperImage = helperImage, imageServer = imageServer)
        case other => unhandled()
      }
      case RequestedDiscard(_) => currentData match {
        case StartData(id, _, _, _, _) => DiscardData(id)
        case other => unhandled()
      }
      case ErrorOccurred(msg, _) => currentData match {
        case errorData: ErrorData => errorData.copy(errors = errorData.errors :+ msg)
        case data: SomeData => ErrorData(data.instanceId, List(msg))
        case NoData => unhandled()
      }
      case ConfirmedDiscard(_) => currentData
      case ConfirmedStart(_) => currentData
      case ConfirmedExit(_) => currentData
      case ConfirmedSave(_) => currentData
      case CommencedUpload(_) => currentData
      case ConfirmedUpload(_) => currentData
    }
  }

  def domainEventClassTag: ClassTag[Instance.DomainEvent] =
    classTag[DomainEvent]

  protected def emitStatusReportToEventStream(state: Instance.State)(data: Instance.Data): Unit =
    context.system.eventStream.publish(StatusReport(state, data))


}