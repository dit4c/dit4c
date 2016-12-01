package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.Props
import org.bouncycastle.openpgp.PGPSecretKeyRing
import dit4c.common.KeyHelpers._
import dit4c.scheduler.domain.{BaseCommand, BaseResponse}
import org.bouncycastle.openpgp.{PGPSecretKey, PGPPublicKey}

object KeyManager {

  def props(armoredPgpSecretKeyBlock: String) =
    Props(classOf[KeyManager],
        parseKeyBlock(armoredPgpSecretKeyBlock).armored)

  def parseKeyBlock(kb: String): PGPSecretKeyRing =
    parseArmoredSecretKeyRing(kb) match {
      case Right(kr) => kr
      case Left(msg) => throw new Exception(msg)
    }

  case class OpenSshKeyPair(`private`: String, `public`: String)

  trait Command extends BaseCommand
  case object GetPublicKeyInfo extends Command
  case object GetOpenSshKeyPairs extends Command

  trait Response extends BaseResponse
  trait GetPublicKeyInfoResponse extends Response
  case class PublicKeyInfo(
      keyFingerprint: String,
      armoredPgpPublicKeyBlock: String) extends GetPublicKeyInfoResponse
  trait GetOpenSshKeyPairsResponse extends Response
  case class OpenSshKeyPairs(
      pairs: List[OpenSshKeyPair]) extends GetOpenSshKeyPairsResponse



}

class KeyManager(armoredPgpSecretKeyBlock: String) extends Actor {
  import KeyManager._
  import scala.collection.JavaConversions._

  val keyring = parseKeyBlock(armoredPgpSecretKeyBlock)

  val receive: Receive = {
    case cmd: Command => cmd match {
      case GetPublicKeyInfo =>
        sender ! PublicKeyInfo(
            keyring.getPublicKey.getFingerprint.map(v => f"$v%02X").mkString,
            keyring.toPublicKeyRing.armored)
      case GetOpenSshKeyPairs =>
        sender ! OpenSshKeyPairs(openSshKeyPairs)

    }
  }

  def authenticationKeys: List[PGPSecretKey] =
    keyring.getSecretKeys
      .filter { sk =>
        import org.bouncycastle.openpgp.PGPKeyFlags._
        hasKeyFlags(sk.getPublicKey)(CAN_AUTHENTICATE)
      }
      .toList
      .sortBy(_.getPublicKey.getCreationTime)
      .reverse

  def openSshKeyPairs: List[OpenSshKeyPair] =
    authenticationKeys
      .flatMap { sk =>
        for {
          priv <- sk.asOpenSSH
          pub <- sk.getPublicKey.asOpenSSH
        } yield OpenSshKeyPair(priv, pub)
      }

  def hasKeyFlags(pk: PGPPublicKey)(flags: Int): Boolean = {
    val selfSignature = pk.getSignaturesForKeyID(
        keyring.getPublicKey.getKeyID).toList.head
    (selfSignature.getHashedSubPackets.getKeyFlags & flags) == flags
  }

}