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
import play.api.Play
import providers.InjectorPlugin
import utils.SpecUtils
import providers.hipache.Hipache

@RunWith(classOf[JUnitRunner])
class RoutingMapControllerSpec extends PlaySpecification with SpecUtils {

  val testImage = "dit4c/dit4c-container-ipython"

  "RoutingMapController" should {

    "have an event feed" in new WithApplication(fakeApp) {
      val controller = app.injector.instanceOf(classOf[RoutingMapController])
      val response = controller.feed(FakeRequest())
      status(response) must_== 200
      contentType(response) must beSome("text/event-stream")
    }
  }
}
