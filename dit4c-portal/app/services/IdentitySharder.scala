package services

import java.util.Base64
import com.softwaremill.tagging._
import akka.actor._
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion
import domain.IdentityAggregate

object IdentitySharder {

  final case class Envelope(identityKey: String, payload: Any)

  def apply(userSharder: ActorRef @@ UserSharder.type)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "IdentityAggregate",
        entityProps = aggregateProps(userSharder),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  // Because identity can be any valid string, we need the ID to be encoded
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case Envelope(identityKey, payload) => (aggregateId(identityKey), payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Envelope(identityKey, _) =>
      aggregateId(identityKey).reverse.take(2).reverse // Last two characters of aggregate ID (it'll do for now)
  }

  private def aggregateId(identityKey: String) = base64Url(identityKey)

  private def base64Url(s: String) = Base64.getUrlEncoder.encodeToString(s.getBytes("utf8")).replaceAll("=", "")

  private def aggregateProps(userAggregateManager: ActorRef @@ UserSharder.type): Props =
    Props(classOf[IdentityAggregate], userAggregateManager)

}