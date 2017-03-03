package domain.accesspass

import com.softwaremill.tagging._
import domain.SchedulerAggregate
import akka.util.ByteString
import akka.actor._
import org.bouncycastle.openpgp._
import dit4c.protobuf.tokens.ClusterAccessPass
import scala.util._
import java.time.Instant
import domain.BaseResponse
import org.apache.commons.lang3.CharUtils
import java.util.Base64
import dit4c.common.KeyHelpers.PGPFingerprint
import akka.event.LoggingReceive

object PassVerificationWorker {

  trait Result extends BaseResponse
  trait GoodResult extends Result
  trait BadResult extends Result
  case class ValidPass(
      cap: ClusterAccessPass,
      expiry: Option[Instant],
      signedBy: PGPFingerprint) extends GoodResult
  case class ExpiredPass(
      cap: ClusterAccessPass) extends BadResult
  case class UnverifiablePass(
      reason: String) extends BadResult

}

class PassVerificationWorker(
    scheduler: ActorRef @@ SchedulerAggregate,
    signedData: ByteString)
      extends Actor with ActorLogging {
  import PassVerificationWorker._
  import dit4c.common.KeyHelpers._

  override def preStart = {
    scheduler ! SchedulerAggregate.GetKeys
  }

  val receive: Receive = LoggingReceive {
    case SchedulerAggregate.CurrentKeys(keyBlock) =>
      context.parent ! resultFromKeyBlock(keyBlock)
      context.stop(self)
    case SchedulerAggregate.NoKeysAvailable =>
      context.parent ! UnverifiablePass("No keys are available to do validation")
      context.stop(self)
  }

  def resultFromKeyBlock(primaryKeyBlock: String): Result =
    keysForVerification(primaryKeyBlock) match {
      case Left(msg) =>
        UnverifiablePass(msg)
      case Right(Nil) =>
        UnverifiablePass("No known key IDs found in signature")
      case Right(keys) =>
        resultFromKeys(keys)
    }

  def resultFromKeys(keys: List[PGPPublicKey]): Result =
    verify(keys) match {
      case Right((cap, expiries)) if expiries.isEmpty =>
        ExpiredPass(cap)
      case Right((cap, expiries)) =>
        // Pick the signature with the longest expiry
        val (signedBy, expires) =
          expiries.reduce[(PGPPublicKey, Option[Instant])] {
            case (p1 @ (k1, None), _) => p1
            case (_, p2 @ (k2, None)) => p2
            case (p1 @ (k1, Some(e1)), p2 @ (k2, Some(e2))) =>
              if (e1.isBefore(e2)) p2
              else p1
          }
        ValidPass(cap, expires, signedBy.fingerprint)
      case Left(msg) =>
        UnverifiablePass(s"Verification failed: $msg")
    }

  def keysForVerification(primaryKeyBlock: String): Either[String, List[PGPPublicKey]] =
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

  def verify(keys: List[PGPPublicKey]): Either[String, (ClusterAccessPass, Map[PGPPublicKey, Option[Instant]])] =
    verifyData(signedData, keys)
      .right.flatMap { case (data: ByteString, expiries) =>
        // Extract token data
        Try(ClusterAccessPass.parseFrom(data.toArray))
          .transform[Either[String, ClusterAccessPass]](
            { cap => Success(Right(cap)) },
            { e => Success(Left(e.getMessage)) }
          )
          .get
          .right.map((_, expiries))
      }

  /**
   * Get a safe form of the signed data for logging.
   */
  protected lazy val dataForLog: String =
    Try(signedData.decodeString("UTF-8"))
      .filter(_.forall(CharUtils.isAsciiPrintable))
      .getOrElse(Base64.getEncoder.encodeToString(signedData.toArray))
}