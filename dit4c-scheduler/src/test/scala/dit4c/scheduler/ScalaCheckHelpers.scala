package dit4c.scheduler

import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import dit4c.scheduler.domain.ClusterAggregate.ClusterTypes
import akka.actor.ActorSystem

object ScalaCheckHelpers extends ScalaCheckHelpers

trait ScalaCheckHelpers {

  implicit val arbClusterType = Arbitrary(Gen.oneOf(ClusterTypes.values.toSeq))

  val genNonEmptyString: Gen[String] =
    Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
      .suchThat(!_.isEmpty)

  val genAggregateId: Gen[String] = Gen.identifier

  def genSystem(prefix: String) =
    for {
      id <- Gen.listOfN(40, Gen.alphaChar).map(_.mkString)
    } yield ActorSystem(s"$prefix-$id")

}