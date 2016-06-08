package dit4c.scheduler.routes

import akka.http.scaladsl.model.HttpHeader
import dit4c.scheduler.Specs2RouteTest
import java.io.ByteArrayOutputStream
import scala.sys.process.BasicIO

class SwaggerRoutesSpec extends Specs2RouteTest {

  val routes = swaggerRoutes(system, "test:12345")

  "Swagger Routes" >> {

    "redirect /swagger/ to /swagger/index.html" >> {
      Get("/swagger/") ~> routes  ~> check {
        ( status must beRedirection ) and
        ( header("Location") must beSome[HttpHeader](
          be_==("index.html") ^^ { (header: HttpHeader) => header.value }
        ))
      }
    }

    "has content at /swagger/index.html" >> {
      Get("/swagger/index.html") ~> routes ~> check {
        status must beSuccess
      }
    }

    "has content at /swagger/swagger.json" >> {
      Get("/swagger/swagger.json") ~> routes ~> check {
        status must beSuccess
      }
    }

  }

}