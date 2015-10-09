package dit4c.machineshop

import org.specs2.mutable.Specification
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model._
import StatusCodes._
import akka.http.scaladsl.testkit.RouteTest
import akka.testkit.TestKit
import dit4c.Specs2TestInterface

class MiscServiceSpec extends Specification with RouteTest with Specs2TestInterface {
  val miscRoute = MiscService("fake-server-id").route

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

    "return server ID for GET requests to /server-id" in {
      Get("/server-id") ~> miscRoute ~> check {
        responseAs[String] must_== "fake-server-id"
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
