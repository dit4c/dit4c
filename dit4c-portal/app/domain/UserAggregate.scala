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
import services.InstanceAggregateManager
import akka.persistence.PersistentActor
import akka.util.Timeout
import java.time.Instant
import domain.{InstanceAggregate => IA }
import scala.collection.immutable.SortedSet
import services.{InstanceAggregateManager => IAM}
import services.InstanceAggregateManager.InstanceEnvelope

object UserAggregate {

  type Id = String

  case class Data(instances: SortedSet[String] = SortedSet.empty)

  sealed trait Command
  case class StartInstance(clusterId: String, image: String) extends Command {
    def toIAMCommand: InstanceAggregateManager.Command =
      (InstanceAggregateManager.StartInstance.apply _)
        .tupled(StartInstance.unapply(this).get)
  }
  case class StartInstanceFromInstance(clusterId: String, sourceInstance: String) extends Command
  case class SaveInstance(instanceId: String) extends Command
  case class DiscardInstance(instanceId: String) extends Command
  case object GetAllInstanceIds extends Command

  sealed trait Response
  case object UnableToStartInstance extends Response
  case object InstanceNotOwnedByUser extends Response
  case class UserInstances(instanceIds: Set[String]) extends Response

  sealed trait DomainEvent { def timestamp: Instant }
  case class CreatedInstance(
      instanceId: String, timestamp: Instant = Instant.now) extends DomainEvent

}

class UserAggregate(
    userId: UserAggregate.Id,
    instanceAggregateManager: ActorRef @@ InstanceAggregateManager) extends PersistentActor with ActorLogging {
  import UserAggregate._
  import play.api.libs.json._
  import akka.pattern.{ask, pipe}

  override lazy val persistenceId = self.path.name

  implicit val m: Materializer = ActorMaterializer()
  import context.dispatcher
  implicit val timeout = Timeout(1.minute)

  var data = Data()

  val http = Http(context.system)

  def receiveCommand: PartialFunction[Any,Unit] = {
    case msg @ StartInstance(clusterId, image) =>
      val op = (instanceAggregateManager ? msg.toIAMCommand)
      op.onSuccess {
        case InstanceAggregateManager.InstanceStarted(instanceId) =>
          self ! InstanceCreationConfirmation(instanceId)
      }
      op pipeTo sender
    case StartInstanceFromInstance(clusterId, sourceInstance) =>
      if (data.instances.contains(sourceInstance)) {
        (instanceAggregateManager ? InstanceEnvelope(sourceInstance, InstanceAggregate.GetImage)).foreach {
          case InstanceAggregate.InstanceImage(image) =>
            val op = (instanceAggregateManager ?
                InstanceAggregateManager.StartInstance(clusterId, image))
            op.onSuccess {
              case InstanceAggregateManager.InstanceStarted(instanceId) =>
                self ! InstanceCreationConfirmation(instanceId)
            }
            op pipeTo sender
          case InstanceAggregate.NoImageExists =>
            sender ! InstanceAggregate.NoImageExists
        }
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case InstanceCreationConfirmation(instanceId) =>
      persist(CreatedInstance(instanceId))(updateData)
    case GetAllInstanceIds =>
      sender ! UserInstances(data.instances)
    case SaveInstance(instanceId) =>
      if (data.instances.contains(instanceId)) {
        instanceAggregateManager forward
          InstanceAggregateManager.InstanceEnvelope(instanceId,
              InstanceAggregate.Save)
      } else {
        sender ! InstanceNotOwnedByUser
      }
    case DiscardInstance(instanceId) =>
      if (data.instances.contains(instanceId)) {
        instanceAggregateManager forward
          InstanceAggregateManager.InstanceEnvelope(instanceId,
              InstanceAggregate.Discard)
      } else {
        sender ! InstanceNotOwnedByUser
      }
  }

  def receiveRecover: PartialFunction[Any,Unit] = {
    case e: DomainEvent => updateData(e)
  }

  val updateData: DomainEvent => Unit = {
    case CreatedInstance(instanceId, _) =>
      data = data.copy(instances = data.instances + instanceId)
  }

  implicit class UriPathHelper(path: Uri.Path) {
    def last = path.reverse.head
  }

  private case class InstanceCreationConfirmation(instanceId: String)

}