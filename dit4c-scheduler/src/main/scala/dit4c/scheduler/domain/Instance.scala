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
import akka.actor.Cancellable

object Instance {
  type Id = String
  type ImageName = String
  type ImageId = String
  type NamedImage = String
  type LocalImage = String
  type KeyData = String

  def props(worker: ActorRef): Props =
    Props(classOf[Instance], worker)

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
  sealed trait SomeData extends Data {
    def instanceId: String
  }
  case class StartData(
      instanceId: String,
      providedImage: String,
      resolvedImage: Option[String],
      portalUri: String,
      keys: Option[InstanceKeys]) extends SomeData
  case class SaveData(
      instanceId: String,
      keys: InstanceKeys,
      imageServer: String,
      portalUri: String) extends SomeData
  case class DiscardData(instanceId: String) extends SomeData
  case class ErrorData(instanceId: String, errors: List[String]) extends SomeData

  trait Command extends BaseCommand
  case object GetStatus extends Command
  case class Initiate(
      instanceId: String,
      image: NamedImage,
      portalUri: String) extends Command
  case class ReceiveImage(id: LocalImage) extends Command
  case class AssociateKeys(key: KeyData) extends Command
  case object ConfirmStart extends Command
  case object ConfirmExited extends Command
  case object Discard extends Command
  case object ConfirmDiscard extends Command
  case class Save(imageServer: String) extends Command
  protected case object ContinueSave extends Command
  protected case object ContinueDiscard extends Command
  protected case object Upload extends Command
  case object ConfirmSaved extends Command
  case object ConfirmUpload extends Command
  case class Error(msg: String) extends Command

  sealed trait Response extends BaseResponse
  case class StatusReport(
      state: Instance.State,
      data: Instance.Data) extends Response
  case object Ack extends Response
}

class Instance(worker: ActorRef)
    extends PersistentFSM[State, Data, DomainEvent] {
  import dit4c.scheduler.domain.instance._
  import Instance._
  import BaseDomainEvent.now
  import dit4c.common.KeyHelpers._

  lazy val persistenceId = self.path.name

  private case object Tick
  private var ticker: Option[Cancellable] = None
  private var workerIsAlive = true

  override def preStart = {
    import context.dispatcher
    context.watch(worker)
    ticker = Some(context.system.scheduler.schedule(5.seconds, 5.second, context.self, Tick))
  }

  override def postStop = {
    if (workerIsAlive) {
      worker ! InstanceWorker.Done
    }
    ticker.foreach(_.cancel)
  }

  startWith(JustCreated, NoData)

  when(JustCreated) {
    case Event(Initiate(id, imageName, callback), _) =>
      val domainEvent = Initiated(id, imageName, callback, now)
      val requester = sender
      goto(WaitingForImage).applying(domainEvent).andThen {
        case data =>
          log.info(s"Fetching image: $imageName")
          worker ! InstanceWorker.Fetch(imageName)
          requester ! Ack
          emitStatusReportToEventStream(stateName)(data)
      }
  }

  when(WaitingForImage, stateTimeout = 1.hour) {
    case Event(ReceiveImage(localImage), StartData(id, _, _, callback, _)) =>
      goto(Starting).applying(FetchedImage(localImage, now)).andThen {
        case data =>
          log.info(s"Starting with: $localImage")
          worker ! InstanceWorker.Start(localImage, callback)
          emitStatusReportToEventStream(stateName)(data)
      }
    case Event(StateTimeout, _) ⇒
      self ! Error("Timeout while fetching image")
      stay
  }

  when(Starting, stateTimeout = 1.hour) {
    case Event(AssociateKeys(keyData), _) =>
      log.info(s"Received keys: $keyData")
      stay.applying(AssociatedKeys(keyData)).andThen(emitStatusReportToEventStream(stateName))
    case Event(ConfirmStart, _) =>
      log.info(s"Confirmed start")
      goto(Running).applying(ConfirmedStart(now))
    case Event(StateTimeout, _) ⇒
      self ! Error("Timeout while starting instance")
      stay
  }

  when(Running) {
    case Event(Save(imageServer), StartData(id, _, _, portalUri, _) ) =>
      val requester = sender
      goto(Stopping).applying(RequestedSave(imageServer, now)).andThen { _ =>
        worker ! InstanceWorker.Stop
        requester ! Ack
      }
    case Event(Discard, StartData(id, _, _, _, _)) =>
      val requester = sender
      goto(Stopping).applying(RequestedDiscard(now)).andThen { _ =>
        worker ! InstanceWorker.Stop
        requester ! Ack
      }
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillRunning)
      stay
    case Event(ConfirmExited, _) =>
      goto(Exited).applying(ConfirmedExit(now))
  }

  when(Stopping) {
    case Event(ConfirmExited, _) =>
      goto(Exited).applying(ConfirmedExit(now))
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillRunning)
      stay
  }

  when(Exited) {
    case Event(Save(imageServer), StartData(id, _, _, portalUri, _) ) =>
      val requester = sender
      goto(Saving).applying(RequestedSave(imageServer, now)).andThen { _ =>
        worker ! InstanceWorker.Save
        requester ! Ack
      }
    case Event(Discard, _) =>
      val requester = sender
      goto(Discarding).applying(RequestedDiscard(now)).andThen { _ =>
        worker ! InstanceWorker.Discard
        requester ! Ack
      }
    case Event(ContinueSave, SaveData(id, _, _, _) ) =>
      worker ! InstanceWorker.Save
      goto(Saving)
    case Event(ContinueDiscard, DiscardData(id)) =>
      worker ! InstanceWorker.Discard
      goto(Discarding)
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillExists)
      stay
  }

  when(Saving, stateTimeout = 4.hours) {
    case Event(ConfirmSaved, _) =>
      goto(Saved).applying(ConfirmedSave(now))
    case Event(StateTimeout, _) ⇒
      self ! Error("Timeout while saving image")
      stay
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillExists)
      stay
  }

  when(Saved) {
    case Event(Upload, SaveData(id, _, imageServer, portalUri)) =>
      val requester = sender
      goto(Uploading).applying(CommencedUpload(now)).andThen { _ =>
        worker ! InstanceWorker.Upload(imageServer, portalUri)
      }
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillExists)
      stay
  }

  when(Uploading, stateTimeout = 4.hours) {
    case Event(ConfirmUpload, _) =>
      goto(Uploaded).applying(ConfirmedUpload(now))
    case Event(Discard, _) =>
      self ! Error("Discard received during upload")
      stay
    case Event(StateTimeout, _) ⇒
      self ! Error("Timeout while uploading image")
      stay
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillExists)
      stay
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
    case Event(Tick, _) =>
      worker ! InstanceWorker.Assert(InstanceWorker.StillExists)
      stay
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
      goto(Errored).applying(ErrorOccurred(msg, now))
    case Event(Tick, _) =>
      // Do nothing
      stay
    case Event(akka.actor.Terminated(ref), _) if ref == worker =>
      workerIsAlive = false
      stop
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
      case Initiated(id, imageName, portalUri, _) => currentData match {
        case NoData => StartData(id, imageName, None, portalUri, None)
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
      case RequestedSave(imageServer, _) => currentData match {
        case StartData(id, _, _, portalUri, Some(signingKey)) =>
          SaveData(id, signingKey, imageServer, portalUri)
        case data: SaveData =>
          // TODO: Get rid of this once we know how it happens
          log.warning("Two requested saves really shouldn't happen!")
          data.copy(imageServer = imageServer)
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

  def domainEventClassTag: ClassTag[DomainEvent] =
    classTag[DomainEvent]

  protected def emitStatusReportToEventStream(state: Instance.State)(data: Instance.Data): Unit =
    context.system.eventStream.publish(StatusReport(state, data))


}