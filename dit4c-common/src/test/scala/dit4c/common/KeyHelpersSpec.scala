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

class KeyHelpersSpec extends Specification with ScalaCheck with AllExpectations {

  import KeyHelpers._

  implicit val params = Parameters(minTestsOk = 20)

  trait KeyBitsTag
  type KeyBits = Int @@ KeyBitsTag

  trait PGPIdentityTag
  type PGPIdentity = String @@ PGPIdentityTag

  trait PassphraseTag
  type Passphrase = String @@ PassphraseTag


  implicit val arbKeyBits: Arbitrary[KeyBits] =
    Arbitrary(Gen.frequency(10 -> 1024, 1 -> 2048).map(_.taggedWith[KeyBitsTag]))
  val genNonEmptyString =
    Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
      .suchThat(!_.isEmpty)
  implicit val arbPGPIdentity = Arbitrary(genNonEmptyString.map(_.taggedWith[PGPIdentityTag]))
  implicit val arbPassphrase = Arbitrary(genNonEmptyString.map(_.taggedWith[PassphraseTag]))
  implicit val arbKeyPair =
    Arbitrary(arbKeyBits.arbitrary.map { bits: KeyBits =>
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(bits)
      kpg.generateKeyPair
    })

  "KeyHelpers" should {

    "produce OpenPGP armoured secret keys" >> prop({ (identity: PGPIdentity, bits: KeyBits, passphrase: Option[Passphrase]) =>
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, passphrase)
      val outputKey = pgpKey.`private`.armored
      val lines = outputKey.lines.toSeq;
      {
        lines must
          haveFirstLine("-----BEGIN PGP PRIVATE KEY BLOCK-----") and
          haveLastLine("-----END PGP PRIVATE KEY BLOCK-----")
      } and {
        import scala.collection.JavaConversions._
        val secretKeyCollection = new PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(outputKey.getBytes)), new JcaKeyFingerprintCalculator())
        secretKeyCollection.getKeyRings.next.getSecretKey must beLike { case sk =>
          val privateKey = sk.extractPrivateKey(passphrase match {
            case None => null
            case Some(passphrase) =>
              new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
          })
          (sk.getKeyID must be_==(pgpKey.getKeyID)) and
          (sk.getUserIDs.next must be_==(identity)) and
          (sk.getPublicKey must beLike({ case pubKey =>
            val desiredFlags = {
              import org.bouncycastle.bcpg.sig.KeyFlags._
              AUTHENTICATION|ENCRYPT_COMMS|ENCRYPT_STORAGE|SIGN_DATA
            }
            val sig = pubKey.getSignaturesForKeyID(pubKey.getKeyID).toList.head
            (sig.getCreationTime must beLessThan(new Date())) and
            // If at least required bits are set, then bit-wise AND of desiredFlags should be desiredFlags
            ((sig.getHashedSubPackets.getKeyFlags & desiredFlags) must_==(desiredFlags))
          })) and
          ({
            val expectedKey = pgpKey.extractPrivateKey(passphrase match {
              case None => null
              case Some(passphrase) =>
                new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
            })
            privateKey.getPrivateKeyDataPacket.getEncoded must_==(expectedKey.getPrivateKeyDataPacket.getEncoded)
          })
        }
      }
    })

    "parse armored public keys" >> prop({ (identity: PGPIdentity, bits: KeyBits) =>
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val outputKey = pgpKey.`public`.armored
      parseArmoredPublicKey(outputKey) must beRight(beLike[PGPPublicKey] {
        case parsedKey =>
          parsedKey.getFingerprint must_==(pgpKey.getPublicKey.getFingerprint)
      })
    })

    "produce PCKS#1 keys from PGP keys" >> prop({ (identity: PGPIdentity, bits: KeyBits, passphrase: Option[Passphrase]) =>
      import sys.process._
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, passphrase)
      val is = new ByteArrayInputStream(pgpKey.asRSAPrivateKey(passphrase).pkcs1.pem.getBytes)
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

    "produce OpenSSH keys from PGP keys" >> prop({ (identity: PGPIdentity, bits: KeyBits) =>
      import sys.process._
      val tmpKeyring = File.createTempFile("keyring", ".kbx")
      tmpKeyring.deleteOnExit()
      val pgpKey = KeyHelpers.PGPKeyGenerators.RSA(identity, bits, None)
      val generatedString = pgpKey.getPublicKey.asOpenSSH
      val armoredPublicKey = pgpKey.`public`.armored;
      val gpgSshKey = {
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
}