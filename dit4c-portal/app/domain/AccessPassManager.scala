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
import org.apache.commons.lang3.CharUtils
import java.util.Base64

object AccessPassManager {

  sealed trait Command extends BaseCommand
  case class RegisterAccessPass(signedData: ByteString) extends Command
  protected case class RegisterVerifiedAccessPass(
      id: String,
      signedData: ByteString) extends Command


  sealed trait Response extends BaseResponse
  trait RegisterAccessPassResponse extends Response
  case class AcceptedAccessPass(id: String) extends RegisterAccessPassResponse
  case class RejectedAccessPass(reason: String) extends RegisterAccessPassResponse

  class AddWorker(requester: ActorRef, scheduler: ActorRef, signedData: ByteString)
      extends Actor with ActorLogging {
    import dit4c.common.KeyHelpers._

    def schedulerId = scheduler.path.name

    override def preStart = {
      scheduler ! SchedulerAggregate.GetKeys
    }

    val receive: Receive = {
      case SchedulerAggregate.CurrentKeys(primaryKeyBlock, _) =>
        verify(primaryKeyBlock) match {
          case Right((clusters, _)) if clusters.isEmpty =>
            log.error(s"Cluster access pass contained no clusters!\n$dataForLog")
            sender ! RejectedAccessPass(s"No clusters in pass")
          case Right((_, expiries)) if expiries.isEmpty =>
            log.error(s"Cluster access pass has no valid signature.!\n$dataForLog")
            sender ! RejectedAccessPass(s"No valid signatures")
          case Right((clusterIds, expiries)) =>
            val id = accessPassId(signedData)
            log.info(s"$id {$scheduler $clusterIds} decoded with valid signatures.")
            context.parent ! RegisterVerifiedAccessPass(
                accessPassId(signedData),
                signedData)
            sender ! AcceptedAccessPass(id)
          case Left(msg) =>
            sender ! RejectedAccessPass(s"Verification failed: $msg")
        }
        context.stop(self)
      case SchedulerAggregate.NoKeysAvailable =>
        sender ! RejectedAccessPass("No keys are available to do validation")
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
                // Extract token data
                Try(ClusterAccessPass.parseFrom(data.toArray))
                  .transform[Either[String, Set[String]]](
                    { cap => Success(Right(cap.clusterIds.toSet)) },
                    { e => Success(Left(e.getMessage)) }
                  )
                  .get
                  .right.map((_, expiries))
              }
        }

    /**
     * Get a safe form of the signed data for logging.
     */
    protected lazy val dataForLog: String =
      Try(signedData.decodeString("UTF-8"))
        .filter(_.forall(CharUtils.isAsciiPrintable))
        .getOrElse(Base64.getEncoder.encodeToString(signedData.toArray))
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
      case RegisterAccessPass(signedData: ByteString) =>
        context.actorOf(
            Props(classOf[AddWorker], sender, context.parent, signedData))
      case _ : RegisterVerifiedAccessPass =>
        // TODO: Implement
    }
  }

}