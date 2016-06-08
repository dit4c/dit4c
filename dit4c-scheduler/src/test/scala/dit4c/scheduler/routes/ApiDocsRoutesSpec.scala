package dit4c.scheduler.routes

import akka.http.scaladsl.model.HttpHeader
import dit4c.scheduler.Specs2RouteTest
import java.io.ByteArrayOutputStream
import scala.sys.process.BasicIO

class ApiDocsRoutesSpec extends Specs2RouteTest {

  val routes = apiDocsRoutes(system, "test:12345")

  "Swagger Routes" >> {

    "redirect /api-docs/ to /api-docs/index.html" >> {
      Get("/api-docs/") ~> routes  ~> check {
        val redirectUrl = "index.html?url=/api-docs/swagger.json"
        ( status must beRedirection ) and
        ( header("Location") must beSome[HttpHeader](
          be_==(redirectUrl) ^^ { (header: HttpHeader) => header.value }
        ))
      }
    }

    "has content at /api-docs/index.html" >> {
      Get("/api-docs/index.html") ~> routes ~> check {
        status must beSuccess
      }
    }

    "has content at /api-docs/swagger.json" >> {
      Get("/api-docs/swagger.json") ~> routes ~> check {
        status must beSuccess
      }
    }

  }

}