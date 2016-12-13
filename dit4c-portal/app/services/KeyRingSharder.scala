package services

import java.util.Base64
import com.softwaremill.tagging._
import akka.actor._
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion
import domain.KeyRingAggregate
import domain.BaseCommand
import dit4c.common.KeyHelpers._

object KeyRingSharder {

  object Envelope {
    def forKeySubmission(keyBlock: String): Either[String, Envelope] =
      parseArmoredPublicKeyRing(keyBlock).right.map { pkr =>
        Envelope(pkr.getPublicKey.fingerprint, KeyRingAggregate.ReceiveKeySubmission(keyBlock))
      }
  }
  
  final case class Envelope(fingerprint: PGPFingerprint, payload: KeyRingAggregate.Command) extends BaseCommand

  def apply()(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "KeyRingAggregate",
        entityProps =  Props(classOf[KeyRingAggregate]),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case Envelope(fingerprint, payload) => (fingerprint.string, payload)
  }

  // Sharding single entities - key ID collisions are not expected
  val extractShardId: ShardRegion.ExtractShardId = {
    case Envelope(fingerprint, _) => fingerprint.keyIdAsString
  }

}