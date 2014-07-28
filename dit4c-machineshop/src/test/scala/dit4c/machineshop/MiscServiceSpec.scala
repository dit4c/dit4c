package dit4c.machineshop

import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.http._
import StatusCodes._
import spray.routing.HttpService

class MiscServiceSpec extends Specification with Specs2RouteTest with HttpService {
  implicit def actorRefFactory = system
  val miscRoute = MiscService().route

  "MiscService" should {

    "return a greeting for GET requests to the root path" in {
      Get() ~> miscRoute ~> check {
        responseAs[String] must contain("DIT4C MachineShop")
      }
    }

    "return favicon for GET requests to /favicon.ico" in {
      Get("/favicon.ico") ~> miscRoute ~> check {
        responseAs[Array[Byte]] must not beEmpty
      }
    }

    "leave GET requests to other paths unhandled" in {
      Get("/kermit") ~> miscRoute ~> check {
        handled must beFalse
      }
    }

    "return a MethodNotAllowed error for PUT requests to the root path" in {
      Put() ~> sealRoute(miscRoute) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: GET"
      }
    }
  }
}
