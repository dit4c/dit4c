package dit4c.gatehouse

import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.http._
import spray.json._
import StatusCodes._
import akka.actor._

class AuthServiceSpec extends Specification with Specs2RouteTest {

  val authService = {
    val system = ActorSystem("testSystem")
    AuthService(system, system.actorOf(Props[AuthServiceMockDockerIndexActor]))
  }
  import authService._

  "AuthService" should {

    "for GET requests to the auth path" >> {

      "return 400 if subdomain label is not present" in {
        Get("/auth") ~> addHeader("Host", "localhost") ~> route ~> check {
          HttpRequest()
          status must be(BadRequest)
          responseAs[String] must beMatching(".*Host header.*".r)
        }
      }

      "return 200 if Host is present and port is found" in {
        Get("/auth") ~> addHeader("Host", "foo.example.com") ~> route ~> check {
          status must be(OK)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beSome
          header("X-Upstream-Port").get.value must_== "40003"
        }
      }

      "return 404 if Host is present and port is not found" in {
        Get("/auth") ~> addHeader("Host", "bar.example.com") ~> route ~> check {
          status must be(NotFound)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beNone
        }
      }

      "return 500 if query fails" in {
        Get("/auth") ~> addHeader("Host", "die.example.com") ~> route ~> check {
          status must be(InternalServerError)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beNone
        }
      }

    }
  }
}

class AuthServiceMockDockerIndexActor extends Actor {
  import dit4c.gatehouse.docker.DockerIndexActor._

  val receive: Receive = {
    case PortQuery("die") =>
      // Do nothing
    case PortQuery("foo") =>
      sender ! PortReply(Some(40003))
    case PortQuery(_) =>
      sender ! PortReply(None)
  }

}
