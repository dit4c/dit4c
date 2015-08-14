package dit4c.gatehouse

import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.http._
import spray.http.HttpHeaders.Cookie
import spray.json._
import StatusCodes._
import akka.actor._

class AuthServiceSpec extends Specification with Specs2RouteTest {

  import AuthServiceSpec._
  val authService = {
    val system = ActorSystem("testSystem")
    AuthService(system,
        system.actorOf(Props[AuthServiceMockDockerIndexActor]),
        system.actorOf(Props[AuthServiceMockAuthActor]))
  }

  import authService._

  "AuthService" should {

    "for GET requests to the auth path" >> {

      "return 400 if X-Server-Name is not present" in {
        Get("/auth") ~> route ~> check {
          status must be(BadRequest)
          responseAs[String] must beMatching(".*X-Server-Name header.*".r)
        }
        // Check dashed domain labels work
        Get("/auth") ~> addHeader("X-Server-Name", "a-b") ~> route ~> check {
          status must not be(BadRequest)
        }
      }

      "return 403 if Host is present and token is missing or invalid" in {
        Get("/auth") ~>
            addHeader("X-Server-Name", "foo") ~>
            route ~> check {
          status must be(Forbidden)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beNone
        }
        Get("/auth") ~>
            addHeader("X-Server-Name", "foo") ~>
            Cookie(HttpCookie("dit4c-jwt", badToken)) ~>
            route ~> check {
          status must be(Forbidden)
          entity must_== HttpEntity(
            ContentTypes.`text/plain(UTF-8)`,"invalid token")
          header("X-Upstream-Port") must beNone
        }
      }

      "return 200 if X-Server-Name is present, token is valid and port is found" in {
        Get("/auth") ~>
            addHeader("X-Server-Name", "foo") ~>
            Cookie(HttpCookie("dit4c-jwt", goodToken)) ~>
            route ~> check {
          status must be(OK)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beSome
          header("X-Upstream-Port").get.value must_== "3.4.5.6:8080"
        }
      }

      "return 404 if Host is present, token is valid and port is not found" in {
        Get("/auth") ~>
            addHeader("X-Server-Name", "bar") ~>
            Cookie(HttpCookie("dit4c-jwt", goodToken)) ~>
            route ~> check {
          status must be(NotFound)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beNone
        }
      }

      "return 500 if port query fails" in {
        Get("/auth") ~>
            addHeader("X-Server-Name", "die") ~>
            Cookie(HttpCookie("dit4c-jwt", goodToken)) ~>
            route ~> check {
          status must be(InternalServerError)
          entity must be(HttpEntity.Empty)
          header("X-Upstream-Port") must beNone
        }
      }

    }
  }
}

object AuthServiceSpec {

  // These would normally be JWS tokens, but we're not testing that here
  val goodToken = "GoodToken"
  val badToken = "BadToken"

  class AuthServiceMockDockerIndexActor extends Actor {
    import dit4c.gatehouse.docker.DockerIndexActor._

    val receive: Receive = {
      case PortQuery("die") =>
        // Do nothing
      case PortQuery("foo") =>
        sender ! PortReply(Some("3.4.5.6:8080"))
      case PortQuery(_) =>
        sender ! PortReply(None)
    }

  }

  class AuthServiceMockAuthActor extends Actor {
    import dit4c.gatehouse.auth.AuthActor._

    val receive: Receive = {
      case AuthCheck(jwt, _) => jwt match {
        case `goodToken` => sender ! AccessGranted
        case `badToken` => sender ! AccessDenied("invalid token")
      }
    }

  }
}
