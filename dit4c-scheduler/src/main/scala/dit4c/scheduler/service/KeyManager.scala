package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.Props
import dit4c.common.KeyHelpers._
import dit4c.scheduler.domain.{BaseCommand, BaseResponse}
import org.bouncycastle.openpgp._
import scala.util.Try
import pdi.jwt.JwtClaim
import java.security.PrivateKey
import pdi.jwt.JwtJson
import pdi.jwt.JwtAlgorithm
import java.security.interfaces.RSAPrivateKey
import dit4c.scheduler.ssh.RemoteShell
import akka.actor.ActorLogging

object KeyManager {

  def props(armoredPgpSecretKeyBlocks: Seq[String]) =
    Props(classOf[KeyManager], armoredPgpSecretKeyBlocks)

  trait Command extends BaseCommand
  case object GetPublicKeyInfo extends Command
  case object GetOpenSshKeyPairs extends Command
  case class SignJwtClaim(claim: JwtClaim) extends Command

  trait Response extends BaseResponse

  trait GetPublicKeyInfoResponse extends Response
  case class PublicKeyInfo(
      keyFingerprint: PGPFingerprint,
      armoredPgpPublicKeyBlock: String) extends GetPublicKeyInfoResponse
  trait SignJwtClaimResponse extends Response
  case class SignedJwtTokens(tokens: List[String]) extends SignJwtClaimResponse
  trait GetOpenSshKeyPairsResponse extends Response
  case class OpenSshKeyPairs(
      pairs: List[RemoteShell.OpenSshKeyPair]) extends GetOpenSshKeyPairsResponse

}

class KeyManager(armoredPgpKeyBlocks: Seq[String]) extends Actor with ActorLogging {
  import KeyManager._
  import scala.collection.JavaConversions._

  private val secretKeyrings = {
    // Converted private key rings
    armoredPgpKeyBlocks
      .map(parseArmoredSecretKeyRing)
      .flatMap(_.right.toSeq)
  }
  private val publicKeyrings = {
    // Actual public key rings
    armoredPgpKeyBlocks.map(parseArmoredPublicKeyRing)
      .flatMap(_.right.toSeq)
  } ++ {
    // Converted private key rings
    secretKeyrings.map(_.toPublicKeyRing)
  }

  val fingerprint = {
    val fingerprints =
      publicKeyrings
        .map(_.getPublicKey.fingerprint)
        .distinct
    if (fingerprints.size != 1) {
      val msg = s"Expected single master key, instead ${fingerprints.size}!\n$fingerprints"
      throw new RuntimeException(msg)
    }
  }

  val secretKeyring =
    secretKeyrings
      .reduceOption[PGPSecretKeyRing] { (kr1, kr2) =>
          kr2.getSecretKeys.foreach { sk =>
            PGPSecretKeyRing.insertSecretKey(kr1, sk)
          }
          kr1
      }
      .getOrElse {
        throw new RuntimeException("No secret keys provided")
      }

  val publicKeyring =
    publicKeyrings.reduce[PGPPublicKeyRing] { (kr1, kr2) =>
      kr2.getPublicKeys.foreach { pk =>
        PGPPublicKeyRing.insertPublicKey(kr1, pk)
      }
      kr1
    }

  override def preStart = {
    openSshKeyPairs match {
      case Nil =>
        log.warning("Key manager started without any valid SSH keys!")
      case keys =>
        log.info(
            s"Key manager started with ${keys.length} SSH keys:\n"+
            keys.map(_.`public`).mkString("\n"))
    }
  }

  val receive: Receive = {
    case cmd: Command => cmd match {
      case GetPublicKeyInfo =>
        sender ! PublicKeyInfo(
            publicKeyring.getPublicKey.fingerprint,
            publicKeyring.armored)
      case GetOpenSshKeyPairs =>
        sender ! OpenSshKeyPairs(openSshKeyPairs)
      case SignJwtClaim(claim) =>
        sender ! SignedJwtTokens(sign(claim))
    }
  }

  def authenticationSecretKeys: List[PGPSecretKey] =
    secretKeyring.authenticationKeys
      .flatMap { pk =>
        Try(secretKeyring.getSecretKey(pk.getFingerprint)).toOption
      }

  def sign(claim: JwtClaim): List[String] =
    authenticationSecretKeys
      .flatMap(_.asJavaPrivateKey)
      .map {
        case k: RSAPrivateKey =>
          JwtJson.encode(claim, k, JwtAlgorithm.RS512)
      }

  def openSshKeyPairs: List[RemoteShell.OpenSshKeyPair] =
    authenticationSecretKeys
      .flatMap { sk =>
        for {
          priv <- sk.asOpenSSH
          pub <- sk.getPublicKey.asOpenSSH
        } yield RemoteShell.OpenSshKeyPair(priv, pub)
      }

  def hasKeyFlags(pk: PGPPublicKey)(flags: Int): Boolean = {
    val selfSignature = pk.getSignaturesForKeyID(
        publicKeyring.getPublicKey.getKeyID).toList.head
    (selfSignature.getHashedSubPackets.getKeyFlags & flags) == flags
  }

}