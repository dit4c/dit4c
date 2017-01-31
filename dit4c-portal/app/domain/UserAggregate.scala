package domain

import akka.actor._
import com.softwaremill.tagging._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import scala.concurrent.duration._
import scala.concurrent.Future
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.persistence.PersistentActor
import akka.util.Timeout
import java.time.Instant
import domain.{InstanceAggregate => IA }
import scala.collection.immutable.SortedSet
import services._
import utils.akka.ActorHelpers

object UserAggregate {

  type Id = String

  type SchedulerAccessPassPair = (String, String)

  case class AvailableCluster(
      schedulerId: String,
      clusterId: String,
      until: Option[Instant])

  case class Data(
      instances: SortedSet[String] = SortedSet.empty,
      accessPasses: SortedSet[SchedulerAccessPassPair] = SortedSet.empty)

  sealed trait Command extends BaseCommand
  case object Create extends Command
  case class StartInstance(
      schedulerId: String,
      clusterId: String,
      image: String) extends Command
  case class StartInstanceFromInstance(
      schedulerId: String,
      clusterId: String,
      sourceInstance: String) extends Command
  case class SaveInstance(instanceId: String) extends Command
  case class DiscardInstance(instanceId: String) extends Command
  case class GetInstanceImageUrl(instanceId: String) extends Command
  case object GetAllInstanceIds extends Command
  case class ReceiveSharedInstance(sourceUserId: String, instanceId: String) extends Command
  case class AddAccessPass(schedulerId: String, signedData: ByteString) extends Command
  protected case class AddAccessPassById(
      schedulerId: String, accessPassId: String) extends Command
  case object GetAvailableClusters extends Command

  sealed trait Response extends BaseResponse
  case class CreateResponse(userId: UserAggregate.Id) extends Response
  case object UnableToStartInstance extends Response
  case object InstanceNotOwnedByUser extends Response
  case class UserInstances(instanceIds: Set[String]) extends Response
  case object InstanceReceived extends Response
  trait AddAccessPassResponse extends Response
  case object AccessPassAdded extends AddAccessPassResponse
  case class AccessPassRejected(reason: String) extends AddAccessPassResponse
  trait GetAvailableClustersResponse extends Response
  case class AvailableClusters(
      clusters: List[AvailableCluster]) extends GetAvailableClustersResponse


}

class UserAggregate(
    instanceSharder: ActorRef @@ InstanceSharder.type,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)
    extends PersistentActor with ActorLogging with ActorHelpers {
  import domain.BaseDomainEvent.now
  import domain.user._
  import UserAggregate._
  import play.api.libs.json._
  import akka.pattern.{ask, pipe}

  val userId: UserAggregate.Id = self.path.name
  override lazy val persistenceId = "User-"+userId

  implicit val m: Materializer = ActorMaterializer()
  import context.dispatcher
  implicit val timeout = Timeout(1.minute)

  var data = Data()

  val http = Http(context.system)

  def receiveCommand: PartialFunction[Any,Unit] = {
    case Create =>
      sender ! CreateResponse(userId)
    case msg @ StartInstance(schedulerId, clusterId, image) =>
      val op = (instanceSharder ? InstanceSharder.StartInstance(
          schedulerId, clusterId, accessPassIds(schedulerId), image))
      op.onSuccess {
        case InstanceAggregate.Started(instanceId) =>
          self ! InstanceCreationConfirmation(instanceId)
      }
      op pipeTo sender
    case StartInstanceFromInstance(schedulerId, clusterId, sourceInstance) =>
      if (data.instances.contains(sourceInstance)) {
        (instanceSharder ? InstanceSharder.Envelope(sourceInstance, InstanceAggregate.GetImage)).foreach {
          case InstanceAggregate.InstanceImage(image) =>
            val op = (instanceSharder ?
                InstanceSharder.StartInstance(schedulerId, clusterId, accessPassIds(schedulerId), image))
            op.onSuccess {
              case InstanceAggregate.Started(instanceId) =>
                self ! InstanceCreationConfirmation(instanceId)
            }
            op pipeTo sender
          case InstanceAggregate.NoImageExists =>
            sender ! InstanceAggregate.NoImageExists
        }
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case GetInstanceImageUrl(instanceId) =>
      if (data.instances.contains(instanceId)) {
        instanceSharder forward InstanceSharder.Envelope(instanceId, InstanceAggregate.GetImage)
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case InstanceCreationConfirmation(instanceId) =>
      persist(CreatedInstance(instanceId, now))(updateData)
    case GetAllInstanceIds =>
      sender ! UserInstances(data.instances)
    case SaveInstance(instanceId) =>
      if (data.instances.contains(instanceId)) {
        instanceSharder forward InstanceSharder.Envelope(instanceId, InstanceAggregate.Save)
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case DiscardInstance(instanceId) =>
      if (data.instances.contains(instanceId)) {
        instanceSharder forward InstanceSharder.Envelope(instanceId, InstanceAggregate.Discard)
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case ReceiveSharedInstance(sourceUserId, instanceId) =>
      if (data.instances.contains(instanceId)) {
        sender ! InstanceReceived
      } else {
        log.info(s"$userId receiving shared instance from $sourceUserId: $instanceId")
        persist(ReceivedSharedInstance(sourceUserId, instanceId, now)) { evt =>
          updateData(evt)
          sender ! InstanceReceived
        }
      }
    case AddAccessPass(schedulerId, signedData) =>
      val requester = sender
      val queryMsg = SchedulerSharder.Envelope(schedulerId,
            AccessPassManager.RegisterAccessPass(signedData))
      (schedulerSharder ? queryMsg).foreach {
        case AccessPass.RegistrationSucceeded(accessPassId) =>
          context.self.tell(
              AddAccessPassById(schedulerId, accessPassId),
              requester)
        case AccessPass.RegistrationFailed(reason) =>
          log.warning(s"$userId failed to add access pass for scheduler $schedulerId: $reason")
          requester ! AccessPassRejected(reason)
      }
    case AddAccessPassById(schedulerId, accessPassId) =>
      if (data.accessPasses.contains((schedulerId, accessPassId))) {
        sender ! AccessPassAdded
      } else {
        persist(AddedAccessPass(schedulerId, accessPassId, now)) { evt =>
          updateData(evt)
          log.info(s"$userId added access pass $accessPassId for scheduler $schedulerId")
          sender ! AccessPassAdded
        }
      }
    case GetAvailableClusters =>
      // Collect available clusters from each scheduler
      data.accessPasses
        .groupBy(_._1) // Group by scheduler
        .mapValues(_.map(_._2)) // key is scheduler, value is a set of access IDs
        .map { case (schedulerId, accessPassIds) =>
          val queryMsg = SchedulerSharder.Envelope(
              schedulerId,
              SchedulerAggregate.GetAvailableClusters(accessPassIds))
          // Get cluster list from scheduler, or fall-back to empty
          (schedulerSharder ? queryMsg)
            .collect[List[AvailableCluster]] {
              case SchedulerAggregate.AvailableClusters(clusters) =>
                clusters
            }
            .recover[List[AvailableCluster]] {
              case e =>
                log.error(e, s"Unable to get clusters for scheduler $schedulerId")
                Nil
            }
        }
        .reduceOption { (aF, bF) =>
          for (a <- aF; b <- bF) yield a ++ b
        }
        .getOrElse(Future.successful(List.empty[UserAggregate.AvailableCluster]))
        .map(AvailableClusters(_))
        .pipeTo(sender)

  }

  def receiveRecover: PartialFunction[Any,Unit] = sealedReceive[DomainEvent] {
    case e: DomainEvent => updateData(e)
  }

  val updateData: DomainEvent => Unit = {
    case CreatedInstance(instanceId, _) =>
      data = data.copy(instances = data.instances + instanceId)
    case ReceivedSharedInstance(_, instanceId, _) =>
      data = data.copy(
          instances = data.instances + instanceId)
    case AddedAccessPass(schedulerId, accessPassId, _) =>
      data = data.copy(
          accessPasses = data.accessPasses + ((schedulerId, accessPassId)))
  }

  implicit class UriPathHelper(path: Uri.Path) {
    def last = path.reverse.head
  }

  private case class InstanceCreationConfirmation(instanceId: String)

  private def accessPassIds(schedulerId: String): List[String] =
    data.accessPasses
      .collect { case (`schedulerId`, accessPassId) => accessPassId }
      .toList

}