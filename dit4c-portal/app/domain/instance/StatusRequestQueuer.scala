package domain.instance

import com.softwaremill.tagging._
import akka.actor._
import dit4c.protobuf.scheduler.outbound.InstanceStateUpdate
import domain.Cluster
import domain.InstanceAggregate
import services.SchedulerSharder
import domain.SchedulerAggregate
import scala.concurrent.duration._

class StatusRequestQueuer(
    instanceId: String,
    clusterId: String,
    schedulerId: String,
    schedulerSharder: ActorRef @@ SchedulerSharder.type)
    extends Actor with ActorLogging {

  var clusterInfo: Option[Cluster.ClusterInfo] = None
  var currentStatus: Option[InstanceAggregate.CurrentStatus] = None

  def receive = {
    case receiver: ActorRef =>
      requestLatest
      context.become(waitingForLatest(receiver :: Nil))
    case _: Cluster.CurrentInfo => // Ignore
    case _: InstanceStateUpdate => // Ignore
  }

  def waitingForLatest(queuedReceivers: List[ActorRef]): Receive =
    ({
      case Cluster.CurrentInfo(info, _) =>
        clusterInfo = Some(info)
      case ci: InstanceAggregate.CurrentStatus =>
        currentStatus = Some(ci)
      case receiver: ActorRef =>
        context.become(waitingForLatest(queuedReceivers :+ receiver))
      case ReceiveTimeout =>
        requestLatest
    }: Receive).andThen(_ => tryReplying(queuedReceivers))

  protected def requestLatest = {
    // Remind actor that something is in progress
    context.setReceiveTimeout(10.seconds)
    // Get cluster status (so we know available actions)
    schedulerSharder ! SchedulerSharder.Envelope(schedulerId,
        SchedulerAggregate.ClusterEnvelope(clusterId,
            Cluster.GetInfo))
  }

  protected def tryReplying(receivers: List[ActorRef]): Unit =
    for {
      ci <- clusterInfo
      cs <- currentStatus
      canSave = ci match {
        case i: Cluster.Active if i.supportsSave => true
        case _ => false
      }
    } yield {
      import InstanceAggregate.InstanceAction._
      import InstanceStateUpdate.InstanceState._
      val actions = fromName(cs.state).get match {
        case STARTED if canSave =>
          Set(Save, Discard)
        case STARTED =>
          Set(Discard)
        case _ =>
          Set.empty

      }
      val response = cs.copy(availableActions = cs.availableActions ++ actions)
      receivers.foreach(_ ! response)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(receive)
    }

}