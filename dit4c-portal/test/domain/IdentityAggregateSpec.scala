package dit4c.portal.domain

import com.softwaremill.tagging._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorSystem
import org.scalacheck.Gen
import akka.testkit.TestProbe
import org.specs2.matcher.Matcher
import scala.util.Random
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import scala.concurrent.Future
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.nio.file.Paths
import domain.IdentityAggregate
import services.UserSharder
import akka.actor.Props
import domain.UserAggregate
import java.util.Base64
import akka.testkit.TestActor
import akka.actor.ActorRef

class IdentityAggregateSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck {

  sealed trait Tags
  trait IdentityIdTag extends Tags
  trait UserIdTag extends Tags

  type IdentityId = String @@ IdentityIdTag
  type UserId = String @@ UserIdTag


  implicit val params = Parameters(minTestsOk = 20)
  val genNonEmptyStr = Gen.identifier.suchThat(!_.isEmpty)

  implicit val arbIdentityId: Arbitrary[IdentityId] = Arbitrary {
    for {
      s <- Gen.identifier.suchThat(!_.isEmpty)
      encoded = Base64.getUrlEncoder.encodeToString(s.getBytes("utf8")).replaceAll("=", "")
    } yield encoded.taggedWith[IdentityIdTag]
  }

  implicit val arbUserId: Arbitrary[UserId] = Arbitrary {
    Gen.identifier.suchThat(!_.isEmpty).map(_.taggedWith[UserIdTag])
  }

  "IdentityAggregate" >> {

    "GetUser" >> {

      "create user if unassociated" >> {
        implicit val system =
          ActorSystem(s"IdentityAggregate-GetUser-Unassociated")

        prop({ (identityId: IdentityId, userId: UserId) =>
          val probe = TestProbe()
          val mockSharderProbe = TestProbe()
          val mockSharder = mockSharderProbe.ref.taggedWith[UserSharder.type]
          val identityAggregate =
            probe.childActorOf(
                Props(classOf[IdentityAggregate], mockSharder),
                identityId)
          probe.send(identityAggregate, IdentityAggregate.GetUser)
          mockSharderProbe.expectMsgType[UserSharder.CreateNewUser.type]
          mockSharderProbe.send(identityAggregate, UserAggregate.CreateResponse(userId))
          probe.expectMsgType[IdentityAggregate.GetUserResponse] must {
            be_==(IdentityAggregate.UserFound(userId))
          }
        })
      }


      "return the same user ID once created" >> {
        implicit val system =
          ActorSystem(s"IdentityAggregate-GetUser-Associated")

        prop({ (identityId: IdentityId, userId: UserId) =>
          val probe = TestProbe()
          val mockSharderProbe = TestProbe()
          mockSharderProbe.setAutoPilot(new TestActor.AutoPilot {
            def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
              msg match {
                case UserSharder.CreateNewUser =>
                  sender ! UserAggregate.CreateResponse(userId)
                  TestActor.NoAutoPilot
                case _ => TestActor.KeepRunning
              }
          })
          val mockSharder = mockSharderProbe.ref.taggedWith[UserSharder.type]
          val identityAggregate =
            probe.childActorOf(
                Props(classOf[IdentityAggregate], mockSharder),
                identityId)
          val responses = Stream.fill(10) {
            probe.send(identityAggregate, IdentityAggregate.GetUser)
            probe.expectMsgType[IdentityAggregate.GetUserResponse]
          }
          responses must allOf(be_==(IdentityAggregate.UserFound(userId)))
        })
      }
    }

  }
}
