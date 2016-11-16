package services

import akka.actor._
import com.softwaremill.tagging._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import domain.InstanceAggregate.Start
import sun.security.jca.GetInstance
import akka.event.LoggingReceive
import domain.UserAggregate
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ClusterShardingSettings

object UserSharder {
  sealed trait Command
  case object CreateNewUser extends Command
  case class Envelope(userId: UserAggregate.Id, msg: Any) extends Command

  def apply(instanceSharder: ActorRef @@ InstanceSharder.type)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "UserAggregate",
        entityProps = aggregateProps(instanceSharder),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  // Because identity can be any valid string, we need the ID to be encoded
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case CreateNewUser => (newUserId, UserAggregate.Create)
    case Envelope(userId, payload) => (userId, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case CreateNewUser => "00" // All user creation will happen in one shard, but that's OK
    case Envelope(userId, _) => userId.reverse.take(2).reverse // Last two characters of aggregate ID (it'll do for now)
  }

  private def aggregateProps(instanceSharder: ActorRef @@ InstanceSharder.type): Props =
    Props(classOf[UserAggregate], instanceSharder)

  private def newUserId: String = f"${BigInt.apply(128, scala.util.Random)}%032x"
}
