package dit4c.scheduler.service

import akka.actor.Actor
import akka.actor.Props
import org.bouncycastle.openpgp.PGPSecretKeyRing
import dit4c.common.KeyHelpers._
import dit4c.scheduler.domain.{BaseCommand, BaseResponse}
import org.bouncycastle.openpgp.{PGPSecretKey, PGPPublicKey}
import scala.util.Try
import pdi.jwt.JwtClaim
import java.security.PrivateKey
import pdi.jwt.JwtJson
import pdi.jwt.JwtAlgorithm
import java.security.interfaces.RSAPrivateKey

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
  case class SignJwtClaim(claim: JwtClaim) extends Command

  trait Response extends BaseResponse

  trait GetPublicKeyInfoResponse extends Response
  case class PublicKeyInfo(
      keyFingerprint: String,
      armoredPgpPublicKeyBlock: String) extends GetPublicKeyInfoResponse
  trait SignJwtClaimResponse extends Response
  case class SignedJwtTokens(tokens: List[String]) extends SignJwtClaimResponse
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
            keyring.getPublicKey.fingerprint,
            keyring.toPublicKeyRing.armored)
      case GetOpenSshKeyPairs =>
        sender ! OpenSshKeyPairs(openSshKeyPairs)
      case SignJwtClaim(claim) =>
        sender ! SignedJwtTokens(sign(claim))
    }
  }

  def authenticationSecretKeys: List[PGPSecretKey] =
    keyring.authenticationKeys
      .flatMap { pk =>
        Try(keyring.getSecretKey(pk.getFingerprint)).toOption
      }

  def sign(claim: JwtClaim): List[String] =
    authenticationSecretKeys
      .flatMap(_.asJavaPrivateKey)
      .map {
        case k: RSAPrivateKey =>
          JwtJson.encode(claim, k, JwtAlgorithm.RS512)
      }

  def openSshKeyPairs: List[OpenSshKeyPair] =
    authenticationSecretKeys
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