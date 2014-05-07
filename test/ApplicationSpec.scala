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

  "Application" should {

    "send 404 on a bad request" in new WithApplication{
      route(FakeRequest(GET, "/boum")) must beNone
    }

    "render the index page" in new WithApplication{
      val home = route(FakeRequest(GET, "/")).get

      status(home) must equalTo(OK)
      contentType(home) must beSome.which(_ == "text/html")
      contentAsString(home) must contain ("Pick a container")
    }

    "callback" in new WithApplication(FakeApplication(
        additionalConfiguration = Map(
          "callback_signature_keys.fakekey" -> "testkey"
        ))) {
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
        val content = "{}"
        val jwsObject = new JWSObject(
            new JWSHeader(JWSAlgorithm.HS512), new Payload(content))
        // Sign with altered key
        jwsObject.sign(new MACSigner("testkey"))
        jwsObject.serialize
      }
      val res = route(base.withBody(AnyContentAsFormUrlEncoded(
          Map("assertion" -> Seq(goodAssertion))))).get
      status(res) must not equalTo(400)
      status(res) must not equalTo(403)
      pending
    }

  }
}
