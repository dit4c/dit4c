package dit4c.scheduler.ssh

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.specs2.specification.AllExpectations
import org.scalacheck.Arbitrary

class RsaFactorizerSpec extends Specification with ScalaCheck with AllExpectations {

  type RsaSimpleFormTuple = (BigInt, BigInt, BigInt) // n, e & d

  val rsaTuples: Gen[RsaSimpleFormTuple] = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    for {
      keyLength <- Gen.frequency(
          100 ->  512,
           10 -> 1024,
            1 -> 2048
        )
    } yield {
      kpg.initialize(keyLength)
      val kp = kpg.genKeyPair()
      val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
      val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
      (pub.getModulus, pub.getPublicExponent, priv.getPrivateExponent)
    }
  }
  val positiveBigInt: Gen[BigInt] = Arbitrary.arbBigInt.arbitrary.map(_.abs)

  "RsaFactorizer" should {
    "factorize k properly" ! prop { (rsaKey: RsaSimpleFormTuple) =>
      val (n, e, d) = rsaKey
      val k = e * d - 1
      val (r, t) = RsaFactorizer.factorizeK(k)
      BigInt(2).pow(t)*r === k
    }.setGen(rsaTuples)

    "generate s series properly" ! prop { (rsaKey: RsaSimpleFormTuple) =>
      val (n, e, d) = rsaKey
      val k = e * d - 1
      val (r, t) = RsaFactorizer.factorizeK(k)
      Range(2,10).map(BigInt(_)).map { x =>
        val sSeries = RsaFactorizer.sSeries(x, k, r, t, n)
        sSeries must (
          contain(beLessThan(n)) and // All elements are (mod N)
          contain(
            // Either 1 or potential candidate to find prime
            be_===(BigInt(1)) or
            beLike[BigInt] { case (si: BigInt) =>
              val p = (si - 1).gcd(n)
              // Note that in some cases, p == 1 here, which is OK
              n % p must_== 0
            }
          ) and
          // Last element is always x^r (mod N)
          (be_===(x.modPow(r,n)) ^^ { (series: Seq[BigInt]) => series.last })
        )
      }.reduce(_ and _)
    }.setGen(rsaTuples)

    "get s candidate properly" ! prop { (rsaKey: RsaSimpleFormTuple) =>
      val (n, e, d) = rsaKey
      val k = e * d - 1
      val (r, t) = RsaFactorizer.factorizeK(k)
      RsaFactorizer.siCandidate(2, k, r, t, n) must (
        beNone or
        beSome(
            beGreaterThan(BigInt(1)) and not
            (be_==(1) ^^ { (si: BigInt) => (si - 1).gcd(n) })
        )
      )
    }.setGen(rsaTuples)

    "should provide PKCS#1 parameters from n, e & d" ! prop { (rsaKey: RsaSimpleFormTuple, m: BigInt) =>
      val (n, e, d) = rsaKey
      val (_, _, _, p, q, dp, dq, qinv) = RsaFactorizer(n, e, d)
      (p * q) === n
      (d % (p - 1)) === dp
      (d % (q - 1)) === dq
      (q - 1) % p === qinv
      // From: https://en.wikipedia.org/wiki/RSA_(cryptosystem)
      // Standard RSA encrypt & decrypt
      m.modPow(e, n).modPow(d, n) === m;
      // Accelerated encrypted & decrypt
      {
        // Encrypt test value
        val c = m.modPow(e, n)
        // Decrypt
        val m1 = c.modPow(dp, p)
        val m2 = c.modPow(dq, q)
        val h = (qinv * (m1 - m2)) % p
        m2 + (h * q) === m
      }
    }.setGen1(rsaTuples).setGen2(positiveBigInt)
  }

}