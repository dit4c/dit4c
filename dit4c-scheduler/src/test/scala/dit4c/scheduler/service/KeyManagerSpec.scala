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

class KeyManagerSpec(implicit ee: ExecutionEnv)
    extends Specification {
  import KeyManager._

  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()

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
            KeyManager.props(fixtureKeyBlock))
      probe.send(manager, GetPublicKeyInfo)
      val response = probe.expectMsgType[GetPublicKeyInfoResponse](1.minute)
      response must beLike[GetPublicKeyInfoResponse] {
        case PublicKeyInfo(fingerprint, keyBlock) =>
          fingerprint must_== "28D6BE5749FA9CD2972E3F8BAD0C695EF46AFF94"
          parseArmoredPublicKeyRing(keyBlock) match {
            case Right(pkr) =>
              pkr.getPublicKey.getFingerprint must_==
                fingerprint.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
            case Left(msg) =>
              ko(msg)
          }
      }
    }

    "provides SSH key pairs" >> {
      val probe = TestProbe()
      val manager =
        probe.childActorOf(
            KeyManager.props(fixtureKeyBlock))
      probe.send(manager, GetOpenSshKeyPairs)
      val response = probe.expectMsgType[GetOpenSshKeyPairsResponse](1.minute)
      response must beLike[GetOpenSshKeyPairsResponse] {
        case OpenSshKeyPairs(pairs) =>
          pairs must haveSize(2)
      }
    }


  }

}