package dit4c.scheduler.service

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import dit4c.scheduler.ScalaCheckHelpers
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import org.specs2.matcher.MatcherMacros
import scala.concurrent.duration._
import pdi.jwt.JwtClaim
import org.specs2.scalacheck.Parameters
import pdi.jwt.JwtJson

class KeyManagerSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck {
  import KeyManager._

  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()
  implicit val params = Parameters(minTestsOk = 5)

  val fixtureKeyBlock: String = {
    import scala.sys.process._
    getClass.getResource("unit_test_scheduler_keys.asc").cat.!!
  }

  "KeyManager" >> {


    "provides public key info" >> {
      import dit4c.common.KeyHelpers._
      val probe = TestProbe()
      val manager =
        probe.childActorOf(
            KeyManager.props(fixtureKeyBlock :: Nil))
      probe.send(manager, GetPublicKeyInfo)
      val response = probe.expectMsgType[GetPublicKeyInfoResponse](1.minute)
      response must beLike[GetPublicKeyInfoResponse] {
        case PublicKeyInfo(fingerprint, keyBlock) =>
          fingerprint must_== PGPFingerprint("28D6BE5749FA9CD2972E3F8BAD0C695EF46AFF94")
          parseArmoredPublicKeyRing(keyBlock) match {
            case Right(pkr) =>
              pkr.getPublicKey.getFingerprint must_=== fingerprint.bytes
            case Left(msg) =>
              ko(msg)
          }
      }
    }

    "provides SSH key pairs" >> {
      val probe = TestProbe()
      val manager =
        probe.childActorOf(
            KeyManager.props(fixtureKeyBlock :: Nil))
      probe.send(manager, GetOpenSshKeyPairs)
      val response = probe.expectMsgType[GetOpenSshKeyPairsResponse](1.minute)
      response must beLike[GetOpenSshKeyPairsResponse] {
        case OpenSshKeyPairs(pairs) =>
          pairs must haveSize(2)
      }
    }

    "signs JWT claims" >> prop({ (claim: String) =>
      import dit4c.common.KeyHelpers._
      val probe = TestProbe()
      val manager =
        probe.childActorOf(
            KeyManager.props(fixtureKeyBlock :: Nil))
      probe.send(manager, SignJwtClaim(JwtClaim(claim)))
      val response = probe.expectMsgType[SignJwtClaimResponse](1.minute)
      response must beLike[SignJwtClaimResponse] {
        case SignedJwtTokens(tokens) =>
          (tokens must haveSize(2)) and
          (tokens must allOf(beLike[String] { case token =>
            parseArmoredSecretKeyRing(fixtureKeyBlock).right.get
              .authenticationKeys
              .flatMap(_.asJavaPublicKey)
              .dropWhile(k => JwtJson.decode(token, k).isSuccess)
              .headOption
              .map(_ => ok)
              .getOrElse(ko)
          }))
      }
    })


  }

}
