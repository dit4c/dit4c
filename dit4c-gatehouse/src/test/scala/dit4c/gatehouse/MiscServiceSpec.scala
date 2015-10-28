package dit4c.gatehouse

import org.specs2.mutable.Specification
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.testkit.RouteTest
import dit4c.Specs2TestInterface
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route

class MiscServiceSpec extends Specification with RouteTest with Specs2TestInterface {
  def actorRefFactory = system

  def miscRoute = MiscService.route

  "MiscService" should {

    "return a greeting for GET requests to the root path" in {
      Get() ~> miscRoute ~> check {
        responseAs[String] must contain("DIT4C Gatehouse")
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
      Put() ~> Route.seal(miscRoute) ~> check {
        status === MethodNotAllowed
        responseAs[String] === "HTTP method not allowed, supported methods: GET"
      }
    }
  }
}
