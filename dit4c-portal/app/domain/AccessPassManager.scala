package domain

import akka.actor._
import org.bouncycastle.openpgp.PGPPublicKey
import java.time.Instant
import dit4c.protobuf.tokens.ClusterAccessPass
import scala.util.Try
import java.security.MessageDigest
import akka.util.ByteString
import scala.util.Success
import org.bouncycastle.openpgp.PGPPublicKeyRing

object AccessPassManager {

  sealed trait Command extends BaseCommand
  case class AddClusterAccessPass(signedData: ByteString) extends Command
  protected case class AddVerifiedClusterAccessPass(
      id: String,
      signedData: ByteString) extends Command


  sealed trait Response extends BaseResponse



  class AddWorker(requester: ActorRef, scheduler: ActorRef, signedData: ByteString) extends Actor {
    import dit4c.common.KeyHelpers._

    override def preStart = {
      scheduler ! SchedulerAggregate.GetKeys
    }

    val receive: Receive = {
      case SchedulerAggregate.CurrentKeys(primaryKeyBlock, _) =>
        verify(primaryKeyBlock) match {
          case Right((clusters, _)) if clusters.isEmpty =>
            // TODO: Fail because there were no clusters indicated
            context.stop(self)
          case Right((_, expiries)) if expiries.isEmpty =>
            // TODO: Fail because there were no valid signatures
            context.stop(self)
          case Right((clusterIds, expiries)) =>
            context.parent ! AddVerifiedClusterAccessPass(
                accessPassId(signedData),
                signedData)
          case Left(msg) =>
            // TODO: notify of failure
            context.stop(self)
        }
      case SchedulerAggregate.NoKeysAvailable =>
        // TODO: notify of failure
        context.stop(self)
    }

    def verify(primaryKeyBlock: String): Either[String, (Set[String], Map[PGPPublicKey, Option[Instant]])] =
      parseArmoredPublicKeyRing(primaryKeyBlock)
        .right.flatMap { kr =>
          extractSignatureKeyIds(signedData)
            .right
            .map((_, kr))
        }
        .right.map { case (keyIds: List[Long], keys: PGPPublicKeyRing) =>
          // Only test with signing keys found in signature block
          keys.signingKeys.filter(k => keyIds.contains(k.getKeyID))
        }
        .right.flatMap {
          case Nil => Left("No known key IDs found in signature")
          case keys =>
            verifyData(signedData, keys)
              .right.flatMap { case (data: ByteString, expiries) =>
                Try(ClusterAccessPass.parseFrom(data.toArray))
                  .transform[Either[String, Set[String]]](
                    { cap => Success(Right(cap.clusterIds.toSet)) },
                    { e => Success(Left(e.getMessage)) }
                  )
                  .get
                  .right.map((_, expiries))
              }
        }
  }

  def accessPassId(signedData: ByteString) = {
    import dit4c.common.KeyHelpers._
    signedData.digest("SHA-512").map(b => f"$b%02x").mkString
  }


}


class AccessPassManager extends Actor with ActorLogging {
  import AccessPassManager._

  val schedulerId = context.parent.path.name

  val receive: Receive = {
    case cmd: Command => cmd match {
      case AddClusterAccessPass(signedData: ByteString) =>
        context.actorOf(
            Props(classOf[AddWorker], sender, context.parent, signedData))
      case _ : AddVerifiedClusterAccessPass =>
        // TODO: Implement
    }
  }

}