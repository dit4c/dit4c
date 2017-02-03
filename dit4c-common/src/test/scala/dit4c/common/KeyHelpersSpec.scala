package dit4c.common

import com.softwaremill.tagging._
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.specs2.specification.AllExpectations
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters
import org.specs2.matcher.Matcher
import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.operator.jcajce._
import org.bouncycastle.openpgp.operator.bc._
import org.bouncycastle.bcpg._
import java.util.Date
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import scala.sys.process.ProcessIO
import java.io.InputStream
import java.security.KeyPair
import java.io.File
import org.specs2.execute.Result
import org.specs2.specification.Scope
import org.specs2.mutable.Around
import org.specs2.execute.AsResult
import scala.util.Random
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import akka.util.ByteString
import scala.util.Try
import java.time.Instant
import java.time.temporal.ChronoUnit

class KeyHelpersSpec extends Specification with ScalaCheck {

  import KeyHelpers._

  implicit val params = Parameters(minTestsOk = 20)

  trait KeyBitsTag
  type KeyBits = Int @@ KeyBitsTag

  trait PGPIdentityTag
  type PGPIdentity = String @@ PGPIdentityTag

  trait PassphraseTag
  type Passphrase = String @@ PassphraseTag

  trait LifetimeTag
  type Lifetime = Long @@ LifetimeTag

  implicit val arbKeyBits: Arbitrary[KeyBits] =
    Arbitrary(Gen.frequency(10 -> 1024, 1 -> 2048).map(_.taggedWith[KeyBitsTag]))
  val genNonEmptyString =
    Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
      .suchThat(!_.isEmpty)
  implicit val arbPGPIdentity = Arbitrary(genNonEmptyString.map(_.taggedWith[PGPIdentityTag]))
  implicit val arbPassphrase = Arbitrary(genNonEmptyString.map(_.taggedWith[PassphraseTag]))
  // PGP stores expiry dates as uint32, so max lifetime reflects that
  implicit val arbLifetime: Arbitrary[Lifetime] =
    Arbitrary(
      Gen.chooseNum(1, 2L * Int.MaxValue - Instant.now.getEpochSecond - 3600)
        .map(_.taggedWith[LifetimeTag]))
  implicit val arbKeyPair =
    Arbitrary(arbKeyBits.arbitrary.map { bits: KeyBits =>
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(bits)
      kpg.generateKeyPair
    })
  implicit val arbByteString =
    Arbitrary(Arbitrary.arbitrary[Array[Byte]].map(ByteString.apply))

  sequential

  "KeyHelpers" should {

    "produce OpenPGP armoured secret keys" >> prop({ (identity: PGPIdentity, bits: KeyBits, passphrase: Option[Passphrase]) =>
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, passphrase)
      val outputKey = pgpKeyRing.armored
      val lines = outputKey.lines.toSeq;
      {
        lines must
          haveFirstLine("-----BEGIN PGP PRIVATE KEY BLOCK-----") and
          haveLastLine("-----END PGP PRIVATE KEY BLOCK-----")
      } and {
        import scala.collection.JavaConversions._
        val secretKeyCollection = new PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(outputKey.getBytes)), new JcaKeyFingerprintCalculator())
        val skr = secretKeyCollection.getKeyRings.next
        (skr.getSecretKey must beLike { case sk =>
          val privateKey = sk.extractPrivateKey(passphrase match {
            case None => null
            case Some(passphrase) =>
              new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
          })
          (sk.getKeyID must be_==(pgpKeyRing.getPublicKey.getKeyID)) and
          (sk.getUserIDs.next must be_==(identity)) and
          (sk.getPublicKey must beLike({ case pubKey =>
            val desiredFlags = {
              import org.bouncycastle.bcpg.sig.KeyFlags._
              CERTIFY_OTHER
            }
            val sig = pubKey.getSignaturesForKeyID(pubKey.getKeyID).toList.head
            (sig.getCreationTime must beLessThan(new Date())) and
            // If at least required bits are set, then bit-wise AND of desiredFlags should be desiredFlags
            ((sig.getHashedSubPackets.getKeyFlags & desiredFlags) must_==(desiredFlags))
          })) and
          ({
            val expectedKey = pgpKeyRing.getSecretKey.extractPrivateKey(passphrase match {
              case None => null
              case Some(passphrase) =>
                new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
            })
            privateKey.getPrivateKeyDataPacket.getEncoded must_==(expectedKey.getPrivateKeyDataPacket.getEncoded)
          })
        }) and (
          skr.getSecretKeys.toList must haveSize(beGreaterThan(1))
        ) and (
          skr.authenticationKeys must not beEmpty
        ) and (
          skr.encryptionKeys must not beEmpty
        ) and (
          skr.signingKeys must not beEmpty
        )
      }
    })

    "parse armored public keys" >> prop({ (identity: PGPIdentity, bits: KeyBits) =>
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val outputKeyBlock = pgpKeyRing.toPublicKeyRing.armored
      parseArmoredPublicKeyRing(outputKeyBlock) must beRight(beLike[PGPPublicKeyRing] {
        case parsedKeyRing =>
          parsedKeyRing.getPublicKey.getFingerprint must_==(pgpKeyRing.getPublicKey.getFingerprint)
      })
    })

    "produce PCKS#1 keys from PGP keys" >> prop({ (identity: PGPIdentity, bits: KeyBits, passphrase: Option[Passphrase]) =>
      import sys.process._
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, passphrase)
      val signingKey = pgpKeyRing.getSecretKey(pgpKeyRing.signingKeys.head.getKeyID)
      val is = new ByteArrayInputStream(signingKey.asRSAPrivateKey(passphrase).pkcs1.pem.getBytes)
      val os = new ByteArrayOutputStream()
      def sendToOs(in: InputStream) = Iterator.continually(in.read).takeWhile(_>=0).foreach(os.write)
      val processIO = new ProcessIO(_ => (), sendToOs, sendToOs, true)
      "openssl rsa -check".#<(is).run(processIO).exitValue must beLike {
        case 0 => ok
        case other =>
          val output = new String(os.toByteArray())
          ko(s"OpenSSL check of key failed\n$output")
      }
    })

    "produce & read PCKS#8 private keys with RSA private keys" >> prop({ (identity: PGPIdentity, kp: KeyPair) =>
      import sys.process._
      val pemStr = kp.getPrivate.pkcs8.pem
      val is = new ByteArrayInputStream(pemStr.getBytes)
      val os = new ByteArrayOutputStream()
      def sendToOs(in: InputStream) = Iterator.continually(in.read).takeWhile(_>=0).foreach(os.write)
      val processIO = new ProcessIO(_ => (), sendToOs, sendToOs, true)
      ("openssl rsa -check".#<(is).run(processIO).exitValue must beLike {
        case 0 => ok
        case other =>
          val output = new String(os.toByteArray())
          ko(s"OpenSSL check of key failed\n$output")
      }) and
      (parsePkcs8PemPrivateKey(pemStr) must_== Right(kp.getPrivate))
    })

    "produce & read PCKS#8 public keys with RSA public keys" >> prop({ (identity: PGPIdentity, kp: KeyPair) =>
      val pemStr = kp.getPublic.pkcs8.pem
      val is = new ByteArrayInputStream(pemStr.getBytes)
      val os = new ByteArrayOutputStream()
      def sendToOs(in: InputStream) = Iterator.continually(in.read).takeWhile(_>=0).foreach(os.write)
      val processIO = new ProcessIO(_ => (), sendToOs, sendToOs, true)
      parsePkcs8PemPublicKey(pemStr) must_== Right(kp.getPublic)
    })

    "produce OpenSSH keys from PGP keys" >> new IfGpgAvailable {
      val identity = "Test Identity"
      val bits = 2048
      val tmpKeyring = File.createTempFile("keyring", ".kbx")
      tmpKeyring.deleteOnExit()
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val generatedString = pgpKeyRing.getPublicKey.asOpenSSH
      val armoredPublicKey = pgpKeyRing.getSecretKey.`public`.armored;
      val gpgSshKey = {
        import sys.process._
        val is = new ByteArrayInputStream(armoredPublicKey.getBytes)
        val gpgCmd = s"gpg2 --no-default-keyring --keyring ${tmpKeyring.getAbsolutePath} --keyid-format 0xlong"
        val keyIdPattern = """key (\w+):""".r.unanchored
        s"$gpgCmd --import".#<(is).exitCodeOutErr match {
          case (0, _, err @ keyIdPattern(keyId)) =>
            // Exporting a master key with --export-ssh-key requires a "!" suffix
            stringToProcess(s"$gpgCmd --trusted-key $keyId --export-ssh-key $keyId!").exitCodeOutErr match {
              case (0, out, _) =>
                // format: <alg_header> <key_data> <comment>
                // Get key without the comment
                out.split(" ").init.mkString(" ")
              case t => throw new Exception(s"gpg ssh export failed: $t")
            }
          case t => throw new Exception(s"gpg import failed: $t")
        }
      }
      generatedString must beSome(gpgSshKey)
    }

    "extract signing key IDs from signed data" >> prop({ (identity: PGPIdentity, bits: KeyBits, testData: ByteString) =>
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val signingKey: PGPPublicKey = Random.shuffle(pgpKeyRing.signingKeys).head
      val signedData: ByteString = signData(pgpKeyRing, signingKey)(testData)
      extractSignatureKeyIds(signedData) must beRight(be_==(List(signingKey.getKeyID)))
    })

    "verify and unwrap PGP signatures" >> prop({ (identity: PGPIdentity, bits: KeyBits, testData: ByteString, lifetime: Option[Lifetime]) =>
      val pgpKeyRing = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val signingKey: PGPPublicKey = Random.shuffle(pgpKeyRing.signingKeys).head
      val creationTime = Instant.now.truncatedTo(ChronoUnit.SECONDS)
      val signedData: ByteString = signData(
          pgpKeyRing, signingKey, creationTime, lifetime)(testData)
      (verifyData(signedData, Nil) must beRight((testData, Map.empty))) and
      (verifyData(signedData, Seq(signingKey)) must beRight(
        (testData, Map(signingKey -> lifetime.map(creationTime.plusSeconds)))
      ))
    })

  }

  def haveFirstLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.head }
  def haveLastLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.last }

  implicit class ProcessBuilderHelper(pb: sys.process.ProcessBuilder) {

    def exitCodeOutErr: (Int, String, String) = {
      import sys.process._
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      def connect(os: OutputStream)(is: InputStream) = Iterator.continually(is.read).takeWhile(_>=0).foreach(os.write)
      val processIO = new ProcessIO(_ => (), connect(out), connect(err), true)
      val exitCode = pb.run(processIO).exitValue
      out.flush
      err.flush
      (exitCode, new String(out.toByteArray, "utf8"), new String(err.toByteArray, "utf8"))
    }

  }

  trait IfGpgAvailable extends Scope with Around {
    override def around[T](t: ⇒ T)(implicit asR: AsResult[T]): Result = {
      import sys.process.{stringToProcess => asProc}
      if (asProc("which gpg2").exitCodeOutErr._1 == 0 && asProc("gpg2 --version").lineStream.head.contains(" 2.1."))
        asR.asResult(t)
      else
        skipped("No usable version of GPG available")
    }
  }

  def signData(
      skr: PGPSecretKeyRing,
      signingKey: PGPPublicKey,
      creationTime: Instant = Instant.now,
      lifetime: Option[Lifetime] = None)(data: ByteString): ByteString = {
    import scala.collection.JavaConversions._
    val selfSignature = signingKey.getSignaturesForKeyID(
      skr.getPublicKey.getKeyID).toList.head
    val keyAlg = signingKey.getAlgorithm
    val hashAlg = selfSignature.getHashedSubPackets.getPreferredHashAlgorithms.head
    val out = new ByteArrayOutputStream()
    val compressedData = new PGPCompressedDataGenerator(
        CompressionAlgorithmTags.ZIP)
    val cOut = compressedData.open(out)
    val signatureGenerator = new PGPSignatureGenerator(
        new JcaPGPContentSignerBuilder(keyAlg, hashAlg))
    signatureGenerator.init(PGPSignature.BINARY_DOCUMENT,
        skr.getSecretKey(signingKey.getKeyID).extractPrivateKey(null))
    // Write one-pass signature header
    signatureGenerator.generateOnePassVersion(false).encode(cOut)
    // Write literal data
    val lOut = (new PGPLiteralDataGenerator()).open(
        cOut, PGPLiteralData.BINARY, "", new Date(), new Array[Byte](1024))
    lOut.write(data.toArray)
    lOut.close()
    // Write signature
    signatureGenerator.update(data.toArray)
    signatureGenerator.setHashedSubpackets({
      val subGen = new PGPSignatureSubpacketGenerator()
      subGen.setSignatureCreationTime(false, Date.from(creationTime))
      lifetime.foreach(subGen.setSignatureExpirationTime(false, _))
      subGen.generate
    })
    signatureGenerator.generate.encode(cOut)
    cOut.flush
    cOut.close
    out.close
    ByteString(out.toByteArray)
  }


}