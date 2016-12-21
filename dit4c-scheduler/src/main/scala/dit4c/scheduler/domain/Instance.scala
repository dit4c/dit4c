package dit4c.scheduler.domain

import scala.util.Random
import akka.persistence.fsm.PersistentFSM
import scala.reflect._
import scala.concurrent.duration._
import java.time.Instant
import java.security.interfaces.{RSAPublicKey => JavaRSAPublicKey}
import akka.actor.Props
import akka.actor.ActorRef
import org.bouncycastle.openpgp.PGPPublicKeyRing
import com.google.protobuf.timestamp.Timestamp
import dit4c.scheduler.domain.instance.DomainEvent
import Instance.{State, Data}

object Instance {

  type Id = String
  type ImageName = String
  type ImageId = String
  type NamedImage = String
  type LocalImage = String
  type KeyData = String

  def props(id: Id, worker: ActorRef): Props =
    Props(classOf[Instance], id, worker)

  object InstanceKeys {
    import dit4c.common.KeyHelpers._
    def apply(k: PGPPublicKeyRing): InstanceKeys = InstanceKeys(k.armored)
  }
  case class InstanceKeys(armoredPgpPublicKeyBlock: String) {
    import dit4c.common.KeyHelpers._
    def asPGPPublicKeyRing: PGPPublicKeyRing = {
      parseArmoredPublicKeyRing(armoredPgpPublicKeyBlock).right.get
    }
  }

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
  sealed trait SomeData extends Data
  case class StartData(
      providedImage: String,
      resolvedImage: Option[String],
      portalUri: String,
      keys: Option[InstanceKeys]) extends SomeData
  case class SaveData(
      keys: InstanceKeys,
      uploadHelperImage: String,
      imageServer: String,
      portalUri: String) extends SomeData
  case object DiscardData extends SomeData
  case class ErrorData(errors: List[String]) extends SomeData

  trait Command extends BaseCommand
  case object GetStatus extends Command
  case class Initiate(
      image: NamedImage,
      portalUri: String) extends Command
  case class ReceiveImage(id: LocalImage) extends Command
  case class AssociateKeys(key: KeyData) extends Command
  case object ConfirmStart extends Command
  case object ConfirmExited extends Command
  case object Discard extends Command
  case object ConfirmDiscard extends Command
  case class Save(
      helperImage: NamedImage,
      imageServer: String) extends Command
  protected case object ContinueSave extends Command
  protected case object ContinueDiscard extends Command
  protected case object Upload extends Command
  case object ConfirmSaved extends Command
  case object ConfirmUpload extends Command
  case class Error(msg: String) extends Command

  sealed trait Response extends BaseResponse
  case class StatusReport(
      instanceId: Id,
      state: Instance.State,
      data: Instance.Data) extends Response
  case object Ack extends Response
}

class Instance(instanceId: String, worker: ActorRef)
    extends PersistentFSM[State, Data, DomainEvent] {
  import dit4c.scheduler.domain.instance._
  import Instance._
  import BaseDomainEvent.now
  import dit4c.common.KeyHelpers._

  lazy val persistenceId = "Instance-$instanceId"

  startWith(JustCreated, NoData)

  when(JustCreated) {
    case Event(Initiate(imageName, portalUri), _) =>
      val domainEvent = Initiated(imageName, portalUri)
      val requester = sender
      goto(WaitingForImage).applying(domainEvent).andThen {
        case data =>
          log.info(s"Fetching image: $imageName")
          worker ! InstanceWorker.Fetch(imageName)
          requester ! Ack
      }
  }

  when(WaitingForImage) {
    case Event(ReceiveImage(localImage), StartData(_, _, portalUri, _)) =>
      goto(Starting).applying(FetchedImage(localImage)).andThen {
        case data =>
          log.info(s"Starting with: $localImage")
          worker ! InstanceWorker.Start(instanceId, localImage, portalUri)
      }
  }

  when(Starting) {
    case Event(AssociateKeys(keyData), _) =>
      log.info(s"Received keys: $keyData")
      stay.applying(AssociatedKeys(keyData)).andThen(emitStatusReportToEventStream(stateName))
    case Event(ConfirmStart, _) =>
      log.info(s"Confirmed start")
      goto(Running).applying(ConfirmedStart(now))
  }

  when(Running) {
    case Event(Save(helperImage, imageServer), StartData(_, _, portalUri, _) ) =>
      val requester = sender
      goto(Stopping).applying(RequestedSave(helperImage, imageServer)).andThen { _ =>
        worker ! InstanceWorker.Stop(instanceId)
        requester ! Ack
      }
    case Event(Discard, StartData(_, _, _, _)) =>
      val requester = sender
      goto(Stopping).applying(RequestedDiscard(now)).andThen { _ =>
        worker ! InstanceWorker.Stop(instanceId)
        requester ! Ack
      }
  }

  when(Stopping) {
    case Event(ConfirmExited, _) =>
      goto(Exited).applying(ConfirmedExit(now))
  }

  when(Exited) {
    case Event(Save(helperImage, imageServer), StartData(_, _, portalUri, _) ) =>
      val requester = sender
      goto(Saving).applying(RequestedSave(helperImage, imageServer)).andThen { _ =>
        worker ! InstanceWorker.Save(instanceId)
        requester ! Ack
      }
    case Event(Discard, _) =>
      val requester = sender
      goto(Discarding).applying(RequestedDiscard(now)).andThen { _ =>
        worker ! InstanceWorker.Discard
        requester ! Ack
      }
    case Event(ContinueSave, SaveData(_, _, _, _) ) =>
      worker ! InstanceWorker.Save(instanceId)
      goto(Saving)
    case Event(ContinueDiscard, DiscardData) =>
      worker ! InstanceWorker.Discard(instanceId)
      goto(Discarding)

  }

  when(Saving) {
    case Event(ConfirmSaved, _) =>
      goto(Saved).applying(ConfirmedSave(now))
  }

  when(Saved) {
    case Event(Upload, SaveData(_, helperImage, imageServer, portalUri)) =>
      val requester = sender
      goto(Uploading).applying(CommencedUpload(now)).andThen { _ =>
        worker ! InstanceWorker.Upload(instanceId, helperImage, imageServer, portalUri)
      }
  }

  when(Uploading) {
    case Event(ConfirmUpload, _) =>
      goto(Uploaded).applying(ConfirmedUpload(now))
  }

  when(Uploaded, stateTimeout = 1.minute) {
    case Event(ConfirmUpload, _) =>
      stay // Idempotent
    case Event(StateTimeout, _) =>
      stop // Done lingering for new commands
  }

  when(Discarding) {
    case Event(ConfirmDiscard, _) =>
      goto(Discarded).applying(ConfirmedDiscard(now))
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
      stay replying StatusReport(instanceId, stateName, data)
    case Event(Error(msg), _) =>
      goto(Errored).applying(ErrorOccurred(msg, now))
  }

  onTransition {
    case (from, to) =>
      Option(stateData).collect {
        case data: SomeData => log.info(s"Instance ${instanceId}: $from → $to")
      }
      emitStatusReportToEventStream(to)(stateData)
  }

  onTransition {
    case (_, Exited) if stateData.isInstanceOf[SaveData] =>
      log.info("continuing save")
      self ! ContinueSave
    case (_, Exited) if stateData == DiscardData =>
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
      case Initiated(imageName, portalUri, _) => currentData match {
        case NoData => StartData(imageName, None, portalUri, None)
        case other => unhandled("Cannot initiate twice")
      }
      case FetchedImage(imageId, _) => currentData match {
        case data: StartData => data.copy(resolvedImage = Some(imageId))
        case other => unhandled()
      }
      case AssociatedKeys(armoredKeyStr, _) => currentData match {
        case data: StartData => data.copy(keys = Some(InstanceKeys(armoredKeyStr)))
        case other => unhandled()
      }
      case RequestedSave(helperImage, imageServer, _) => currentData match {
        case StartData(_, _, portalUri, Some(signingKey)) =>
          SaveData(signingKey, helperImage, imageServer, portalUri)
        case data: SaveData =>
          // TODO: Get rid of this once we know how it happens
          log.warning("Two requested saves really shouldn't happen!")
          data.copy(uploadHelperImage = helperImage, imageServer = imageServer)
        case other => unhandled()
      }
      case RequestedDiscard(_) => currentData match {
        case StartData(_, _, _, _) => DiscardData
        case other => unhandled()
      }
      case ErrorOccurred(msg, _) => currentData match {
        case errorData: ErrorData => errorData.copy(errors = errorData.errors :+ msg)
        case data: SomeData => ErrorData(List(msg))
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

  def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  protected def emitStatusReportToEventStream(state: Instance.State)(data: Instance.Data): Unit =
    context.system.eventStream.publish(StatusReport(instanceId, state, data))


}