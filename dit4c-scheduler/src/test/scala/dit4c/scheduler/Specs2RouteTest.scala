package dit4c.scheduler

import akka.http.scaladsl.testkit.RouteTest
import akka.http.scaladsl.model.StatusCode
import org.specs2.mutable.Specification

trait Specs2RouteTest extends Specification
    with RouteTest with Specs2TestFrameworkInterface {

  val beSuccess = beLike[StatusCode] {
    case sc if sc.isSuccess == true => ok
    case sc => ko(s"Status code is not for success: $sc")
  }

}