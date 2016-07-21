package dit4c.scheduler.utils

import java.security.interfaces._
import java.util.Base64
import java.security.KeyPairGenerator
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.security.MessageDigest
import java.security.PublicKey
import java.security.PrivateKey

object KeyHelpers {

  object KeyPairGenerators {
    object RSA {
      def apply(bits: Int): (RSAPrivateKey, RSAPublicKey) = {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(bits)
        val kp = kpg.generateKeyPair
        (
          kp.getPrivate.asInstanceOf[RSAPrivateKey],
          kp.getPublic.asInstanceOf[RSAPublicKey]
        )
      }
    }
  }

  implicit class ByteArrayHelper(bytes: Array[Byte]) {
    def base64: String = Base64.getEncoder.encodeToString(bytes)
    def digest(alg: String) = MessageDigest.getInstance(alg).digest(bytes)
  }

  trait KeyFormat {
    protected def raw: Array[Byte]
  }

  /**
   * Key where raw format is DER-encoded
   */
  trait DerEncoded extends KeyFormat {
    def der = raw
  }

  /**
   * Key which has PEM representation
   */
  trait PemEncodable extends KeyFormat {
    protected def pemName: String
    def pem =
      raw                                  // Get encoded key
        .base64                            // Encode as base64 string
        .grouped(64)                       // Group 64 characters per line (RFC1421)
        .toSeq
        .+:(s"-----BEGIN ${pemName}-----") // Prepend header line
        .:+(s"-----END ${pemName}-----")   // Append footer line
        .mkString("\n")                    // Join lines into a single string
        .+("\n")                           // Append final newline
  }

  class SshPublicKey(val raw: Array[Byte], opensshKeyType: String) extends PemEncodable {
    def pemName = "SSH2 PUBLIC KEY"
    def authorizedKeys = s"${opensshKeyType} ${raw.base64}"
    def fingerprint(alg: String): String = {
      val digest = MessageDigest.getInstance(alg).digest(raw)
      alg.toUpperCase match {
        case "MD5" => raw.digest("MD5").map(b => f"${b}%02x").mkString(":")
        case alg => s"${alg.replace("-","")}:${raw.digest(alg).base64.stripSuffix("=")}"
      }
    }
  }

  implicit class PublicKeyHelper(key: PublicKey) {
    def pkcs8: DerEncoded with PemEncodable = new DerEncoded with PemEncodable {
      def raw = key.getEncoded
      override def pemName = "PUBLIC KEY"
    }
  }

  implicit class RSAPublicKeyHelper(key: RSAPublicKey) extends PublicKeyHelper(key) {
    def ssh = {
      import java.nio.ByteBuffer
      // As per RFC4251, string/mpint are represented by uint32 length then bytes
      def lengthThenBytes(bs: Array[Byte]): Array[Byte] =
        ByteBuffer.allocate(4).putInt(bs.length).array() ++ bs
      val bytes =
        lengthThenBytes("ssh-rsa".getBytes("us-ascii")) ++
        lengthThenBytes(key.getPublicExponent.toByteArray) ++
        lengthThenBytes(key.getModulus.toByteArray)
      new SshPublicKey(bytes, "ssh-rsa")
    }
  }

  implicit class PrivateKeyHelper(key: PrivateKey) {
    def pkcs8: DerEncoded with PemEncodable = new DerEncoded with PemEncodable {
      def raw = key.getEncoded
      override def pemName = "PRIVATE KEY"
    }
  }

  implicit class RSAPrivateKeyHelper(key: RSAPrivateKey) extends PrivateKeyHelper(key) {
    def pkcs1: DerEncoded with PemEncodable = new DerEncoded with PemEncodable {
      def raw = PrivateKeyInfo.getInstance(pkcs8.der).parsePrivateKey.toASN1Primitive.getEncoded
      override def pemName = "RSA PRIVATE KEY"
    }
  }

}