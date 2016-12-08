package domain

import com.softwaremill.tagging._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import akka.actor._
import akka.testkit.TestProbe
import akka.util.ByteString
import org.scalacheck.{Arbitrary, Gen}

class AccessPassSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck {

  sealed trait Tags
  trait ActorNameTag extends Tags
  type ActorName = String @@ ActorNameTag

  implicit val arbActorName: Arbitrary[ActorName] =
    Arbitrary(Gen.identifier.map(_.taggedWith[ActorNameTag]))
  implicit val arbByteString =
    Arbitrary(Arbitrary.arbitrary[Array[Byte]].map(ByteString(_)))

  "AccessPass" should {

    "register" should {

      "check data matches ID" >> prop({ (id: ActorName, data: ByteString) =>
        implicit val system = ActorSystem("AccessPass-register-dataMatchesId")
        val schedulerProbe = TestProbe()
        val apmProbe = TestProbe()
        val accessPass = apmProbe.childActorOf(
            Props(
                classOf[AccessPass],
                schedulerProbe.ref.taggedWith[SchedulerAggregate]),
            id)
        apmProbe.send(accessPass, AccessPass.Register(data))
        schedulerProbe.expectNoMsg
        val msg = apmProbe.expectMsgType[AccessPass.RegistrationFailed]
        msg.reason must (contain("match") and contain("data"))
      })
    }

  }

}