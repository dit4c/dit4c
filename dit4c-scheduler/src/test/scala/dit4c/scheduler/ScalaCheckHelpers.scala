package dit4c.scheduler

import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import dit4c.scheduler.domain.ClusterAggregate.ClusterTypes

object ScalaCheckHelpers extends ScalaCheckHelpers

trait ScalaCheckHelpers {

  implicit val arbClusterType = Arbitrary(Gen.oneOf(ClusterTypes.values.toSeq))

  val genNonEmptyString: Gen[String] =
    Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
      .suchThat(!_.isEmpty)

  val genAggregateId: Gen[String] = Gen.alphaStr.suchThat(!_.isEmpty)

}