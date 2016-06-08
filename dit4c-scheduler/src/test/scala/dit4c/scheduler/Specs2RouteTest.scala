package dit4c.scheduler

import akka.http.scaladsl.testkit.RouteTest
import akka.http.scaladsl.model.StatusCode
import org.specs2.mutable.Specification

trait Specs2RouteTest extends Specification
    with RouteTest with Specs2TestFrameworkInterface {

  val beRedirection = beTrue ^^ { (sc: StatusCode) => sc.isRedirection }
  val beSuccess = beTrue ^^ { (sc: StatusCode) => sc.isSuccess }

}