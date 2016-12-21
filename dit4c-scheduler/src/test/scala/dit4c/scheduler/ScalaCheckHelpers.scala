package dit4c.scheduler

import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri

object ScalaCheckHelpers extends ScalaCheckHelpers

trait ScalaCheckHelpers {

  implicit val arbUri = Arbitrary(genUri)

  val genNonEmptyString: Gen[String] =
    Gen.oneOf(Gen.alphaStr, Arbitrary.arbString.arbitrary)
      .suchThat(!_.isEmpty)

  val genAggregateId: Gen[String] =
    Gen.identifier.suchThat(!_.isEmpty)

  val genUri: Gen[Uri] =
    for {
      scheme <- Gen.oneOf[String]("http", "https")
      host <- Gen.const("example.test")
      port <- Gen.chooseNum(1, 0xFFFF, 80, 8080, 443, 4443)
      path <- Gen.const(Uri./.path)
    } yield Uri(scheme, Uri.Authority(Uri.NamedHost(host), port), path=path)

  def genSystem(prefix: String) =
    for {
      id <- Gen.listOfN(40, Gen.alphaChar).map(_.mkString)
    } yield ActorSystem(s"$prefix-$id")

}