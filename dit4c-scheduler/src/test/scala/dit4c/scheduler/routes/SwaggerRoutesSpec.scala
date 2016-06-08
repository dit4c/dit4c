package dit4c.scheduler.routes

import akka.http.scaladsl.model.HttpHeader
import dit4c.scheduler.Specs2RouteTest

class SwaggerRoutesSpec extends Specs2RouteTest {

  "Swagger Routes" >> {

    "redirect /swagger/ to /swagger/index.html" >> {
      Get("/swagger/") ~> swaggerRoutes ~> check {
        ( status must beRedirection ) and
        ( header("Location") must beSome[HttpHeader](
          be_==("index.html") ^^ { (header: HttpHeader) => header.value }
        ))
      }
    }

  }

}