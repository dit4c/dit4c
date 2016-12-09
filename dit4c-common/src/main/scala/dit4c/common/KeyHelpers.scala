package dit4c.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.security.Key
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

import scala.util.Try

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.RSAPublicBCPGKey
import org.bouncycastle.bcpg.RSASecretBCPGKey
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRing
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRing
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.jcajce.JcaPGPPublicKeyRingCollection
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

import akka.util.ByteString
import pdi.jwt.JwtBase64
import play.api.libs.json.JsObject
import play.api.libs.json.Json

object KeyHelpers {

  object PGPKeyGenerators {
    object RSA {
      def apply(
          identity: String,
          bits: Int = 2048,
          passphrase: Option[String] = None): PGPSecretKeyRing = {
        // Necessary because of the way BouncyCastle specifies message digests (even though algs are natively supported)
        Security.addProvider(new BouncyCastleProvider())
        val pair = {
          val kpg = new RSAKeyPairGenerator()
          val publicExponent = BigInt("10001", 16)
          kpg.init(new RSAKeyGenerationParameters(publicExponent.bigInteger, new SecureRandom(), bits, 256))
          kpg.generateKeyPair
        }
        val keyPair = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, pair, new Date())
        // Various flags/preferences we want to embed into the certificate (unalterable after signing)
        val hashedSubpacketVector = {
          import org.bouncycastle.openpgp.PGPKeyFlags._
          import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags._
          import org.bouncycastle.bcpg.HashAlgorithmTags._
          val s = new PGPSignatureSubpacketGenerator()
          // This key can do almost practically anything
          s.setKeyFlags(false, CAN_AUTHENTICATE|CAN_CERTIFY|CAN_ENCRYPT_COMMS|CAN_ENCRYPT_STORAGE|CAN_SIGN)
          // AES is good. Use AES.
          s.setPreferredSymmetricAlgorithms(false, Array(AES_256, AES_192, AES_128))
          // Preference for SHA512 (fast on 64-bit), then for common
          s.setPreferredHashAlgorithms(false, Array(SHA512, SHA256, SHA1, SHA384, SHA224))
          s.generate()
        }
        val secretKeyEncryptor =
          passphrase
              .map { p =>
                new JcePBESecretKeyEncryptorBuilder(
                    SymmetricKeyAlgorithmTags.AES_256,
                    sha1DigestCalculator).setProvider("BC").build(p.toArray)
              }
              .getOrElse(null)
        val secretKey = new PGPSecretKey(
            PGPSignature.DEFAULT_CERTIFICATION,
            keyPair, identity, sha1DigestCalculator, hashedSubpacketVector, null,
            new JcaPGPContentSignerBuilder(
                keyPair.getPublicKey().getAlgorithm(),
                HashAlgorithmTags.SHA256),
            secretKeyEncryptor)
        new BcPGPSecretKeyRing(secretKey.getEncoded)
      }
    }
  }

  def parseArmoredKeyRing[A <: PGPKeyRing](
      gen: InputStream => List[A])(s: String): Either[String, A] = {
    val in = new ByteArrayInputStream(s.getBytes)
    try {
      val is = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(in)
      gen(is) match {
        case Nil => Left("No keys in specified data")
        case key :: Nil => Right(key)
        case xs => Left(s"Data should have contained a single master key, but contained ${xs.length}")
      }
    } catch {
      case e: Throwable => Left(s"Unable to parse key: ${e.getMessage}")
    } finally {
      in.close
    }
  }

  def parseArmoredSecretKeyRing(s: String): Either[String, PGPSecretKeyRing] =
    parseArmoredKeyRing[PGPSecretKeyRing]({ is: InputStream =>
      import scala.collection.JavaConversions._
      (new BcPGPSecretKeyRingCollection(is)).getKeyRings.toList
    })(s)

  def parseArmoredPublicKeyRing(s: String): Either[String, PGPPublicKeyRing] =
    parseArmoredKeyRing[PGPPublicKeyRing]({ is: InputStream =>
      import scala.collection.JavaConversions._
      (new JcaPGPPublicKeyRingCollection(is)).getKeyRings.toList
    })(s)

  def parsePkcs8PemPrivateKey(s: String): Either[String, PrivateKey] =
    parsePkcs8PemKey(s).right.flatMap {
      case v: PrivateKey => Right(v)
      case _ => Left("Not a private key")
    }

  def parsePkcs8PemPublicKey(s: String): Either[String, PublicKey] =
    parsePkcs8PemKey(s).right.flatMap {
      case v: PublicKey => Right(v)
      case _ => Left("Not a public key")
    }

  protected def parsePkcs8PemKey(s: String): Either[String, Key] = {
    val converter = new JcaPEMKeyConverter().setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    Try((new PEMParser(new StringReader(s))).readObject)
      .map(Right.apply)
      .getOrElse(Left("Unable to parse key"))
      .right.flatMap {
        case v: PrivateKeyInfo => Right(converter.getPrivateKey(v))
        case v: SubjectPublicKeyInfo => Right(converter.getPublicKey(v))
        case _ => Left("Not a key")
      }
  }

  def extractSignatureKeyIds(signedData: ByteString): Either[String, List[Long]] =
    Try({
      import scala.collection.JavaConversions._
      val in = PGPUtil.getDecoderStream(signedData.toInputStream)
      // Structure of packets in signature
      // CompressedData
      //  - One-Pass Signature Header
      //  - Literal Data
      //  - Signature
      val cData = (new JcaPGPObjectFactory(in)).nextObject.asInstanceOf[PGPCompressedData]
      val objF = new JcaPGPObjectFactory(cData.getDataStream)
      objF.nextObject.asInstanceOf[PGPOnePassSignatureList]
        .map(_.getKeyID)
        .toList
    }).map[Either[String, List[Long]]](Right(_))
      .recover({ case e => Left(e.getMessage) })
      .get

  /**
   * Verify a signature with a series of candidate public keys.
   * @return data and a map of keys to signature expiry times
   */
  def verifyData(signedData: ByteString, pks: Seq[PGPPublicKey]):
      Either[String, (ByteString, Map[PGPPublicKey, Option[Instant]])] =
    Try({
      import scala.collection.JavaConversions._
      val in = PGPUtil.getDecoderStream(signedData.toInputStream)
      // Structure of packets in signature
      // CompressedData
      //  - One-Pass Signature Header
      //  - Literal Data
      //  - Signature
      val cData = (new JcaPGPObjectFactory(in)).nextObject.asInstanceOf[PGPCompressedData]
      val objF = new JcaPGPObjectFactory(cData.getDataStream)
      val pkOpsMap: Map[PGPPublicKey, PGPOnePassSignature] =
        objF.nextObject.asInstanceOf[PGPOnePassSignatureList].toSeq
          .flatMap { ops =>
            pks.find(ops.getKeyID == _.getKeyID).map(pk => (pk, ops))
          }
          .toMap
      pkOpsMap.foreach {
        case (pk, ops) =>
          ops.init(new BcPGPContentVerifierBuilderProvider(), pk)
      }
      val lData = objF.nextObject.asInstanceOf[PGPLiteralData]
      val content = {
        val bsb = ByteString.newBuilder
        Stream.continually(lData.getInputStream.read)
          .takeWhile(_ >= 0)
          .map(_.toByte)
          .foreach { b =>
            bsb.putByte(b)
            pkOpsMap.values.foreach { ops =>
              ops.update(b)
            }
          }
        bsb.result
      }
      val expiryTimes: Map[PGPPublicKey, Option[Instant]] =
        (for {
          sig <- objF.nextObject.asInstanceOf[PGPSignatureList]
          pk <- pkOpsMap.keys.find(_.getKeyID == sig.getKeyID)
          ops <- pkOpsMap.get(pk) if ops.verify(sig)
        } yield {
          val creationTime = sig.getHashedSubPackets.getSignatureCreationTime.toInstant
          val expiryTime = Some(sig.getHashedSubPackets.getSignatureExpirationTime)
            .filter(_ > 0)
            .map(creationTime.plusSeconds)
          (pk -> expiryTime)
        }).toMap
      (content, expiryTimes)
    }).map[Either[String, (ByteString, Map[PGPPublicKey, Option[Instant]])]](Right(_))
      .recover {
        case e =>
          def msgs(e: Throwable): List[String] =
            Option(e.getMessage).getOrElse(e.getClass.getCanonicalName) ::
            Option(e.getCause).map(msgs).getOrElse(Nil)
          Left(msgs(e).mkString(" ← "))
      }
      .get

  /**
    * At the moment DIT4C assumes instances use a single public key for all actions. This may at some change though
    * (most likely to support elliptic curve cryptography) so DIT4C uses key blocks that could later store a master key
    * and sub-keys.
    */
  def checkInstancePublicKeyRingIsSuitable(kr: PGPPublicKeyRing): Either[String, PGPPublicKeyRing] = {
    import org.bouncycastle.bcpg.PublicKeyAlgorithmTags._
    import org.bouncycastle.openpgp.PGPKeyFlags._
    import scala.collection.JavaConversions._
    val k = kr.getPublicKey()
    // Helpers
    val keyAlgUsableWithJWTs = Set(RSA_GENERAL, ECDH, ECDSA) // One key can't do both ECDH & ECDSA though
    val selfSignature = k.getSignaturesForKeyID(k.getKeyID).toList.head
    def hasKeyFlags(flags: Int) = (selfSignature.getHashedSubPackets.getKeyFlags & flags) == flags
    // Matching
    k match {
      case k if !hasKeyFlags(CAN_AUTHENTICATE) =>
        Left("Key is not usable for authentication, which DIT4C requires")
      case k if !(k.isEncryptionKey && hasKeyFlags(CAN_ENCRYPT_COMMS|CAN_ENCRYPT_STORAGE)) =>
        Left("Key is not usable for encryption, which DIT4C may at some future point require")
      case k if !hasKeyFlags(CAN_SIGN) =>
        Left("Key is not usable for signing, which DIT4C requires")
      case k if !keyAlgUsableWithJWTs.contains(k.getAlgorithm) =>
        Left("Key is not convertible to a JSON Web Key, which DIT4C requires")
      case k => Right(kr) // It's all good!
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

  /**
   * Key where raw format is DER-encoded
   */
  trait OpenPgpData extends KeyFormat {
    def binary: Array[Byte] = captureOutputStream(encodeToStream)

    def armored: String = new String(captureOutputStream { os =>
      val aos = new ArmoredOutputStream(os)
      encodeToStream(aos)
      aos.close
    }, "ASCII")

    override def raw = binary

    protected def encodeToStream(os: OutputStream): Unit

    private def captureOutputStream(f: OutputStream => Unit): Array[Byte] = {
      val out = new ByteArrayOutputStream()
      f(out)
      out.close
      out.toByteArray
    }
  }

  implicit class PGPSecretKeyHelper(secretKey: PGPSecretKey) {
    def `private` = new OpenPgpData {
      override def encodeToStream(os: OutputStream) = secretKey.encode(os)
    }
    def public = new OpenPgpData {
      override def encodeToStream(os: OutputStream) = secretKey.getPublicKey.encode(os)
    }
    def asRSAPrivateKey(passphrase: Option[String]): RSAPrivateKey = {
      val decryptor =
        passphrase
            .map { pass =>
              new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(pass.toCharArray)
            }
            .getOrElse(null)
      val priv = secretKey.extractPrivateKey(decryptor).getPrivateKeyDataPacket.asInstanceOf[RSASecretBCPGKey]
      val pub = secretKey.extractPrivateKey(decryptor).getPublicKeyPacket.getKey.asInstanceOf[RSAPublicBCPGKey]
      val factory = KeyFactory.getInstance("RSA", "BC")
      val params = new RSAPrivateCrtKeySpec(
          priv.getModulus, pub.getPublicExponent, priv.getPrivateExponent, priv.getPrimeP, priv.getPrimeQ,
          priv.getPrimeExponentP, priv.getPrimeExponentQ, priv.getCrtCoefficient)
      factory.generatePrivate(params).asInstanceOf[RSAPrivateKey]
    }
  }

  implicit class PGPSecretKeyRingHelper(skr: PGPSecretKeyRing) {
    def toPublicKeyRing: PGPPublicKeyRing = {
      import scala.collection.JavaConversions._
      val masterKeyRing: PGPPublicKeyRing = new BcPGPPublicKeyRing(skr.getPublicKey.binary)
      skr.getPublicKeys.foldRight(masterKeyRing) { (k, kr) =>
        PGPPublicKeyRing.insertPublicKey(kr, k)
      }
    }
  }

  implicit class PGPKeyRingSubKeyHelper(keyring: PGPKeyRing) {
    import org.bouncycastle.openpgp.PGPKeyFlags._
    import scala.collection.JavaConversions._

    def authenticationKeys: List[PGPPublicKey] =
      publicKeys
        .filter(hasKeyFlags(CAN_AUTHENTICATE))
        .sortBy(_.getCreationTime)
        .reverse

    def encryptionKeys: List[PGPPublicKey] =
      publicKeys
        .filter(hasKeyFlags(CAN_ENCRYPT_COMMS | CAN_ENCRYPT_STORAGE))
        .sortBy(_.getCreationTime)
        .reverse

    def signingKeys: List[PGPPublicKey] =
      publicKeys
        .filter(hasKeyFlags(CAN_SIGN))
        .sortBy(_.getCreationTime)
        .reverse

    def publicKeys: List[PGPPublicKey] =
      keyring.getPublicKeys.collect {
        case pk: PGPPublicKey => pk
      }
      .toList

    protected def hasKeyFlags(flags: Int)(pk: PGPPublicKey): Boolean = {
      val selfSignature = pk.getSignaturesForKeyID(
          keyring.getPublicKey.getKeyID).toList.head
      (selfSignature.getHashedSubPackets.getKeyFlags & flags) == flags
    }
  }

  implicit class PGPKeyRingHelper(kr: PGPKeyRing) extends OpenPgpData {
    override def encodeToStream(os: OutputStream) = kr.encode(os)
  }

  implicit class PGPPublicKeyHelper(publicKey: PGPPublicKey) extends OpenPgpData {
    def asRSAPublicKey: RSAPublicKey = {
      val k = publicKey.getPublicKeyPacket.getKey.asInstanceOf[RSAPublicBCPGKey]
      KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(k.getModulus, k.getPublicExponent))
        .asInstanceOf[RSAPublicKey]
    }
    override def encodeToStream(os: OutputStream) = publicKey.encode(os)
  }



  implicit class PGPPublicKeyJwkHelper(publicKey: PGPPublicKey) {
    def asJWK: Option[JsObject] =
      Some(publicKey.getPublicKeyPacket.getKey)
        .collect {
          case k: RSAPublicBCPGKey =>
            Json.obj(
                "kty" -> "RSA",
                "e" -> base64Url(k.getPublicExponent),
                "n" -> base64Url(k.getModulus))
        }

    protected def base64Url(bi: BigInt): String =
      JwtBase64.encodeString(bi.toByteArray)
  }

  implicit class PGPSecretKeyConversionHelper(secretKey: PGPSecretKey) {
    def asOpenSSH: Option[String] =
      toPrivateKeyInfo.map { keyInfo =>
        import org.bouncycastle.openssl.MiscPEMGenerator
        import org.bouncycastle.openssl.jcajce.JcaPEMWriter
        val writer = new StringWriter()
        val pemWriter = new JcaPEMWriter(writer)
        pemWriter.writeObject(new MiscPEMGenerator(keyInfo))
        pemWriter.close
        writer.close
        writer.toString
      }

    def asJavaPrivateKey: Option[PrivateKey] =
      toPrivateKeyInfo.map(new JcaPEMKeyConverter().getPrivateKey(_))

    protected def toPrivateKeyInfo: Option[PrivateKeyInfo] = {
      val sk = secretKey.extractPrivateKey(null).getPrivateKeyDataPacket
      val pk = secretKey.getPublicKey.getPublicKeyPacket.getKey
      Some((sk, pk))
        .collect[PrivateKeyInfo] {
          case (sk: RSASecretBCPGKey, pk: RSAPublicBCPGKey) =>
            import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
            import org.bouncycastle.asn1.pkcs.{ RSAPrivateKey => ASN1RSAPrivateKey }
            import org.bouncycastle.asn1.x509.AlgorithmIdentifier
            val asn1Key: ASN1RSAPrivateKey =
              new ASN1RSAPrivateKey(
                  sk.getModulus,
                  pk.getPublicExponent,
                  sk.getPrivateExponent,
                  sk.getPrimeP,
                  sk.getPrimeQ,
                  sk.getPrimeExponentP,
                  sk.getPrimeExponentQ,
                  sk.getCrtCoefficient)
            new PrivateKeyInfo(
              new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption),
              asn1Key)
        }
    }

  }

  object PGPFingerprint {
    class InvalidFingerprintException(msg: String) extends Exception
    def apply(bytes: Array[Byte]): PGPFingerprint = apply(ByteString(bytes))
    def apply(s: String): PGPFingerprint = apply(toByteString(s))
    def apply(bytes: ByteString) =
      if (bytes.length == 20) new PGPFingerprint(bytes)
      else throw new InvalidFingerprintException(
          s"PGP fingerprints are 160-bit, not ${bytes.length * 8}")
    def toByteString(s: String): ByteString =
      ByteString(
        s.toUpperCase
          .filter(c => c.isDigit || 'A'.to('F').contains(c))
          .grouped(2)
          .map(Integer.parseInt(_, 16).toByte)
          .toSeq : _*)
  }

  class PGPFingerprint protected (immutableBytes: ByteString) {
    def bytes: Array[Byte] = immutableBytes.toArray
    def string = bytes.map(v => f"$v%02X").mkString
    def keyId = ByteBuffer.wrap(bytes.takeRight(8)).getLong
    override def toString = string
    override def equals(obj: Any) = obj match {
      case other: PGPFingerprint => bytes.deep == other.bytes.deep
      case _ => false
    }
  }

  implicit class PGPPublicKeyFingerprintHelper(publicKey: PGPPublicKey) {
    def fingerprint = PGPFingerprint(publicKey.getFingerprint)
  }

  implicit class PGPPublicKeyOpenSSHHelper(publicKey: PGPPublicKey) {
    def asOpenSSH: Option[String] =
      Some(publicKey.getPublicKeyPacket.getKey)
        .collect {
          case k: RSAPublicBCPGKey =>
            publicKey.asRSAPublicKey.ssh.authorizedKeys
        }
  }

  implicit class PGPPublicKeyJavaPublicKeyHelper(publicKey: PGPPublicKey) {
    def asJavaPublicKey: Option[PublicKey] = scala.util.Try(publicKey.asRSAPublicKey).toOption
  }

  protected def sha1DigestCalculator =
    new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)

  protected def sha256DigestCalculator =
    new JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build().get(HashAlgorithmTags.SHA256)

  // Based heavily on akka-http2-support's ByteStringInputStream
  implicit class ByteStringInputStreamHelper(bs: ByteString) {
    def toInputStream: InputStream = bs match {
      case cs: ByteString.ByteString1C => new ByteArrayInputStream(bs.toArray)
      case _ => bs.compact.toInputStream
    }
    def base64: String = bs.toArray.base64
    def digest(alg: String) = bs.toArray.digest(alg)
  }


}