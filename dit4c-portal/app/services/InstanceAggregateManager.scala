package services

import akka.actor._
import com.softwaremill.tagging._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import domain.InstanceAggregate.RecordInstanceStart
import sun.security.jca.GetInstance
import akka.event.LoggingReceive

object InstanceAggregateManager {

  sealed trait Command
  case class StartInstance(
      clusterId: String, image: String, callback: Uri) extends Command
  case class InstanceEnvelope(instanceId: String, msg: Any) extends Command

}

class InstanceAggregateManager(
    val clusterAggregateManager: ActorRef @@ ClusterAggregateManager)
    extends Actor with ActorLogging {
  import InstanceAggregateManager._
  import services.ClusterAggregateManager.ClusterEnvelope
  import domain.ClusterAggregate
  import akka.pattern.{ask, pipe}
  import context.dispatcher

  val receive: Receive = LoggingReceive {
    case StartInstance(clusterId, image, callback) =>
      implicit val timeout = Timeout(1.minute)
      val requester = sender
      (clusterAggregateManager ? ClusterEnvelope(clusterId,
          ClusterAggregate.StartInstance(image, callback))).flatMap {
        case ClusterAggregate.InstanceStarted(clusterId, instanceId) =>
          (instanceRef(instanceId) ? RecordInstanceStart(clusterId)).flatMap {
            case InstanceAggregate.Ack =>
              instanceRef(instanceId) ? InstanceAggregate.GetStatus
          } pipeTo requester
      }
    case InstanceEnvelope(instanceId, msg) =>
      instanceRef(instanceId) forward msg
  }

  def instanceRef(instanceId: String) = {
    context.child(aggregateId(instanceId)).getOrElse {
      val agg = context.actorOf(
          aggregateProps(instanceId), aggregateId(instanceId))
      context.watch(agg)
      agg
    }
  }

  private def aggregateId(instanceId: String) = s"Instance-$instanceId"

  private def aggregateProps(instanceId: String): Props =
    Props(classOf[InstanceAggregate], instanceId, clusterAggregateManager)


}