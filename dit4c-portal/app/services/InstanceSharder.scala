package services

import akka.actor._
import pdi.jwt._
import scala.util._
import com.softwaremill.tagging._
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import scala.concurrent.duration._
import domain.InstanceAggregate
import domain.InstanceAggregate.Start
import akka.event.LoggingReceive
import utils.IdUtils
import domain.SchedulerAggregate
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ClusterShardingSettings

object InstanceSharder {

  sealed trait Command
  case class StartInstance(
      schedulerId: String,
      clusterId: String,
      accessPassIds: List[String],
      image: String) extends Command
  case class VerifyJwt(token: String) extends Command
  case class Envelope(instanceId: String, msg: Any) extends Command

  def apply(schedulerSharder: ActorRef @@ SchedulerSharder.type)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
        typeName = "InstanceAggregate",
        entityProps = aggregateProps(schedulerSharder),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
  }

  // Because identity can be any valid string, we need the ID to be encoded
  def extractEntityId(implicit system: ActorSystem): ShardRegion.ExtractEntityId = {
    case StartInstance(schedulerId, clusterId, accessPassIds, image) =>
      (newInstanceId, Start(schedulerId, clusterId, accessPassIds, image))
    case Envelope(instanceId, msg) => (instanceId, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case StartInstance(_, _, _, _) => "00" // All instance creation will happen in one shard, but that's OK
    case Envelope(userId, _) => userId.reverse.take(2).reverse // Last two characters of aggregate ID (it'll do for now)
  }

  private def newInstanceId = IdUtils.timePrefix+IdUtils.randomId(16)

  private def aggregateProps(schedulerSharder: ActorRef @@ SchedulerSharder.type): Props =
    Props(classOf[InstanceAggregate], schedulerSharder)

  def resolveJwtInstanceId(token: String): Either[String, String] =
    JwtJson.decode(token, JwtOptions(signature=false))
        .toOption.toRight("Unable to decode token")
        .right.flatMap { claim =>
          val issuerPrefix = "instance-"
          claim.issuer match {
            case Some(id) if id.startsWith(issuerPrefix) =>
              Right(id.stripPrefix(issuerPrefix))
            case Some(id) =>
              Left("Invalid issuer format")
            case None =>
              Left("No issuer defined")
          }
        }
}