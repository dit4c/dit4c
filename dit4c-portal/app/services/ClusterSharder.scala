package services

import akka.actor._
import com.softwaremill.tagging._
import domain.ClusterAggregate
import akka.event.LoggingReceive
import akka.cluster.sharding._

object ClusterSharder {

  case class Envelope(clusterId: String, msg: Any)

  def apply(schedulerSharder: ActorRef @@ SchedulerSharder.type)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "ClusterAggregate",
        entityProps = Props(classOf[ClusterAggregate], schedulerSharder),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case Envelope(id, payload) => (id, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Envelope(id, _) => id.reverse.take(2).reverse // Last two characters of ID (it'll do for now)
  }

}
