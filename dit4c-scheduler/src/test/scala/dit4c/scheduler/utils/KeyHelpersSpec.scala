package dit4c.scheduler.utils

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.specs2.specification.AllExpectations
import org.scalacheck.Arbitrary
import dit4c.scheduler.ScalaCheckHelpers
import org.specs2.scalacheck.Parameters
import org.specs2.matcher.Matcher
import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp._
import org.bouncycastle.openpgp.operator.jcajce._
import org.bouncycastle.openpgp.operator.bc._
import org.bouncycastle.bcpg._

class KeyHelpersSpec extends Specification with ScalaCheck with ScalaCheckHelpers with AllExpectations {

  implicit val params = Parameters(minTestsOk = 20)

  type RSAKeyTuple = (RSAPrivateKey, RSAPublicKey)

  val genRsaTuples: Gen[RSAKeyTuple] = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    for {
      keyLength <- Gen.frequency(
           10 -> 1024,
            1 -> 2048
        )
    } yield {
      kpg.initialize(keyLength)
      val kp = kpg.genKeyPair()
      val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
      val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
      (priv, pub)
    }
  }

  "KeyHelpers" should {
    import KeyHelpers._

    implicit val arbRSAKeyTuple = Arbitrary(genRsaTuples)
    // We never want an empty string for these checks
    implicit val arbString = Arbitrary(genNonEmptyString)

    "produce OpenPGP armoured secret keys" ! prop { (kp: RSAKeyTuple, identity: String, passphrase: Option[String]) =>
      val pgpKey = kp.openpgp(identity, passphrase)
      val outputKey = pgpKey.`private`.armoured
      val outputKeyStr = new String(outputKey, "utf8")
      val lines = outputKeyStr.lines.toSeq;
      {
        lines must
          haveFirstLine("-----BEGIN PGP PRIVATE KEY BLOCK-----") and
          haveLastLine("-----END PGP PRIVATE KEY BLOCK-----")
      } and {
        import scala.collection.JavaConversions._
        val secretKeyCollection = new PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(new ByteArrayInputStream(outputKey)), new JcaKeyFingerprintCalculator())
        secretKeyCollection.getKeyRings.next.getSecretKey must beLike { case sk =>
          val privateKey = sk.extractPrivateKey(passphrase match {
            case None => null
            case Some(passphrase) =>
              new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(passphrase.toCharArray)
          })
          (sk.getKeyID must be_==(pgpKey.getKeyID)) and
          (sk.getUserIDs.next must be_==(identity)) and
          (privateKey.getPrivateKeyDataPacket must beLike {
            case k: RSASecretBCPGKey =>
              (k.getModulus must_== kp._1.getModulus) and
              (k.getPrivateExponent must_== kp._1.getPrivateExponent)
          }) and
          (privateKey.getPublicKeyPacket.getKey must beLike {
            case k: RSAPublicBCPGKey =>
              (k.getModulus must_== kp._2.getModulus) and
              (k.getPublicExponent must_== kp._2.getPublicExponent)
          })
        }
      }
    }
  }

  def haveFirstLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.head }
  def haveLastLine(s: String) = (be_==(s)) ^^ { (xs: Seq[String]) => xs.last }


}