import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.test._
import play.api.test.Helpers._
import play.api.mvc._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApplicationSpec extends Specification {

  import testing.TestUtils.fakeApp

  "Application" should {

    "send 404 on a bad request" in new WithApplication(fakeApp) {
      route(FakeRequest(GET, "/boum")) must beNone
    }

    "render the index page" in new WithApplication(fakeApp) {
      val home = route(FakeRequest(GET, "/")).get

      status(home) must equalTo(OK)
      contentType(home) must beSome.which(_ == "text/html")
      contentAsString(home) must contain ("Pick a container")
    }

    "callback" in new WithApplication(fakeApp) {
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
             |    "edupersonscopedaffiliation": "staff@fictional.edu.au",
             |    "edupersonprincipalname": "tatkin4@fictional.edu.au"
             |  }
             |}""".stripMargin
        val jwsObject = new JWSObject(
            new JWSHeader(JWSAlgorithm.HS512), new Payload(content))
        // Sign with altered key
        jwsObject.sign(new MACSigner("testkey"))
        jwsObject.serialize
      }
      val res = route(base
          .withBody(AnyContentAsFormUrlEncoded(
              Map("assertion" -> Seq(goodAssertion))))
          .withSession("redirect-on-callback" -> "http://example.test/")).get
      status(res) must not equalTo(400)
      status(res) must not equalTo(403)
      redirectLocation(res) must beSome("http://example.test/")
    }

  }
}
