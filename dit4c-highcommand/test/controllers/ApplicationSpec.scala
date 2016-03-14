package controllers

import java.util.UUID
import scala.concurrent.ExecutionContext
import org.junit.runner.RunWith
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import org.specs2.runner.JUnitRunner
import models._
import providers.auth.Identity
import play.api.test.WithApplication
import play.api.mvc.AcceptExtractors
import providers.InjectorPlugin
import utils.SpecUtils

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApplicationSpec extends PlaySpecification with SpecUtils {

  import scala.concurrent.ExecutionContext.Implicits.global

  "Application controller" should {

    "provide a waiting resource" >> {
      "without query string" in new WithApplication(fakeApp) {
        val extractors = new AcceptExtractors {}
        import extractors.Accepts

        val controller = injector(app).instanceOf(classOf[Application])
        val action = controller.waiting("http","example.test","foo")
        val htmlResponse = action(
          FakeRequest().withHeaders("Accept" -> Accepts.Html.mimeType))
        status(htmlResponse) must_== 200
        contentAsString(htmlResponse) must contain("window.location.href = 'http://example.test/foo';") 

        val notHtml = Seq(Accepts.JavaScript, Accepts.Json, Accepts.Xml)
        notHtml.foreach { accept =>
          val nonHtmlResponse = action(
            FakeRequest().withHeaders("Accept" -> accept.mimeType))
          status(nonHtmlResponse) must_== 302
          redirectLocation(nonHtmlResponse) must
            beSome("http://example.test/foo")
          val cacheControl = header("Cache-Control", nonHtmlResponse)
          cacheControl must beSome
          cacheControl.get must contain("no-cache")
        }
      }

      "with query string" in new WithApplication(fakeApp) {
        val extractors = new AcceptExtractors {}
        import extractors.Accepts

        val controller = injector(app).instanceOf(classOf[Application])
        val action = controller.waiting("http","example.test","foo")
        val htmlResponse = action(
          FakeRequest("GET", "/foo?a=1&b=2").withHeaders("Accept" -> Accepts.Html.mimeType))
        status(htmlResponse) must_== 200
        contentAsString(htmlResponse) must contain("window.location.href = 'http://example.test/foo?a=1&b=2';") 

        val notHtml = Seq(Accepts.JavaScript, Accepts.Json, Accepts.Xml)
        notHtml.foreach { accept =>
          val nonHtmlResponse = action(
            FakeRequest("GET", "/foo?a=1&b=2").withHeaders("Accept" -> accept.mimeType))
          status(nonHtmlResponse) must_== 302
          redirectLocation(nonHtmlResponse) must
            beSome("http://example.test/foo?a=1&b=2")
        }
      }
    }

    "provide a health check endpoint" >> {
      
      "for HEAD" in new WithApplication(fakeApp) {
        val controller = injector(app).instanceOf(classOf[Application])
        val action = controller.health(true)
        val response = action(FakeRequest())
        status(response) must_== 200
      }
      
      "for GET" in new WithApplication(fakeApp) {
        val controller = injector(app).instanceOf(classOf[Application])
        val action = controller.health(false)
        val response = action(FakeRequest())
        status(response) must_== 204
      }
      
    }

  }

}
