package domain.instance

import com.softwaremill.tagging._
import akka.actor._
import dit4c.protobuf.scheduler.outbound.InstanceStateUpdate
import domain.Cluster
import domain.InstanceAggregate
import services.SchedulerSharder
import domain.SchedulerAggregate
import scala.concurrent.duration._

class StatusUpdateProcessor(
    instanceId: String,
    clusterId: String,
    schedulerId: String,
    schedulerSharder: ActorRef @@ SchedulerSharder.type,
    statusBroadcaster: ActorRef @@ StatusBroadcaster.type)
    extends Actor with ActorLogging {

  var clusterInfo: Option[Cluster.ClusterInfo] = None
  var currentStatus: Option[InstanceAggregate.CurrentStatus] = None

  override def preStart = {
    requestLatest
  }

  def receive: Receive = ({
    case Cluster.CurrentInfo(info, _) =>
      clusterInfo = Some(info)
    case ci: InstanceAggregate.CurrentStatus =>
      currentStatus = Some(ci)
    case ReceiveTimeout =>
      requestLatest
  }: Receive).andThen(_ => tryBroadcasting)

  protected def requestLatest = {
    // Remind actor that something is in progress
    context.setReceiveTimeout(10.seconds)
    // Get cluster status (so we know available actions)
    schedulerSharder ! SchedulerSharder.Envelope(schedulerId,
        SchedulerAggregate.ClusterEnvelope(clusterId,
            Cluster.GetInfo))
  }

  protected def tryBroadcasting: Unit =
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
        case STARTED | EXITED if canSave =>
          Set(Save, Discard)
        case STARTED | EXITED =>
          Set(Discard)
        case _ =>
          Set.empty

      }
      val msg = StatusBroadcaster.InstanceStatusBroadcast(
          instanceId,
          cs.copy(availableActions = cs.availableActions ++ actions))
      log.debug(s"Broadcasting: $msg")
      statusBroadcaster ! msg
      context.setReceiveTimeout(Duration.Undefined)
      context.stop(self)
    }

}