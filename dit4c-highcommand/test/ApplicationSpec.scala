import akka.util.Timeout
import org.specs2.mutable._
import org.specs2.runner._
import org.specs2.time.NoTimeConversions
import org.junit.runner._
import play.api.test._
import play.api.test.Helpers._
import play.api.mvc._
import scala.concurrent.duration._
import akka.util.Timeout.durationToTimeout
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import testing.TestUtils.fakeApp
import utils.SpecUtils
import scala.util.Random

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApplicationSpec
    extends PlaySpecification with SpecUtils {

  import scala.concurrent.ExecutionContext.Implicits.global
  import play.api.http.HeaderNames._

  override implicit def defaultAwaitTimeout: Timeout = 60.seconds

  "Application" >> {

    "client-side app manages" >> {

      "index" >> {
        val call = controllers.routes.Application.main("")
        call.method must_== "GET"
        call.url must_== "/"
      }

      "login" >> {
        val call = controllers.routes.Application.main("login")
        call.method must_== "GET"
        call.url must_== "/login"
      }

      "waiting" >> {
        val call = controllers.routes.Application.waiting(
          "https", "example.test", "foo")
        call.method must_== "GET"
        call.url must_== "/waiting/https/example.test/foo"
      }

      "health" >> {
        val headCall = controllers.routes.Application.health(true)
        headCall.method must_== "HEAD"
        headCall.url must_== "/health"
        val getCall = controllers.routes.Application.health(false)
        getCall.method must_== "GET"
        getCall.url must_== "/health"
      }

    }

    "index page" in new WithApplication(fakeApp) {
      val home = route(FakeRequest(GET, "/")).get

      status(home) must_== 200
      header(ETAG, home) must beSome
      header("Cache-Control", home) must beSome("public, max-age=1")
    }

    "waiting" in new WithApplication(fakeApp) {
      Seq("", "foo", "foo?a=1&b=2").foreach { path =>
        val urlStr = s"/waiting/https/example.test/$path"
        val waiting = route(FakeRequest(GET, urlStr)
          .withHeaders("Accept" -> "text/html"))
          .get

        status(waiting) must_== 200
        contentAsString(waiting) must contain(s"https://example.test/$path")
        header(ETAG, waiting) must beSome
        header("Cache-Control", waiting) must beSome("public, max-age=1")
      }
    }

    "callback" in new WithApplication(fakeApp) {
      createTestKey(app)

      def base = FakeRequest(POST, "/auth/callback")

      val badRequests = Seq(
        route(base.withBody(AnyContentAsText("foo"))),
        route(base.withBody(
            AnyContentAsFormUrlEncoded(Map("missingassertion" -> Seq("foo"))))),
        route(base.withBody(
            AnyContentAsFormUrlEncoded(Map("assertion" -> Seq("foo")))))
      )

      badRequests.foreach { req =>
        val res = req.get
        status(res) must equalTo(400)
      }

      val goodAssertion = {
        import com.nimbusds.jose._
        import com.nimbusds.jose.crypto._
        val content =
          """|{
             |  "iss": "https://rapid.aaf.edu.au",
             |  "iat": 1397181481,
             |  "jti": "2bdmqlYf2Pmtyu_d4TT7MZ9xtcc44q9A",
             |  "nbf": 1397181421,
             |  "exp": 1397181601,
             |  "typ": "authnresponse",
             |  "aud": "https://example.test/",
             |  "https://aaf.edu.au/attributes": {
             |    "cn": "Tom Atkins",
             |    "mail": "t.atkins@fictional.edu.au",
             |    "displayname": "Mr Tom Atkins",
             |    "givenname": "Tom",
             |    "surname": "Atkins",
             |    "edupersontargetedid": "https://rapid.aaf.edu.au!http://example.test!YnDRIDYdzevtpdas",
             |    "edupersonscopedaffiliation": "staff@fictional.edu.au"
             |  }
             |}""".stripMargin
        val jwsObject = new JWSObject(
            new JWSHeader(JWSAlgorithm.HS256), new Payload(content))
        // Sign with altered key
        jwsObject.sign(new MACSigner(
            app.configuration.getString("rapidaaf.key", None).get))
        jwsObject.serialize
      }
      val res = route(base
          .withBody(AnyContentAsFormUrlEncoded(
              Map("assertion" -> Seq(goodAssertion))))).get
      status(res) must not equalTo(400)
      status(res) must not equalTo(403)
      redirectLocation(res) must beSome("/login")
    }

  }
}
