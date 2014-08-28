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
import scala.concurrent.Future
import play.api.mvc.AnyContentAsEmpty
import utils.SpecUtils
import providers.hipache.Hipache.Backend
import providers.hipache.Hipache

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ComputeNodeControllerSpec extends PlaySpecification with SpecUtils {
  import play.api.Play.current

  val testImage = "dit4c/dit4c-container-ipython"

  "ComputeNodeController" should {

    "provide JSON list of compute nodes" in new WithApplication(fakeApp) {
      val dao = injector.getInstance(classOf[ComputeNodeDAO])
      val controller = injector.getInstance(classOf[ComputeNodeController])
      val session = new UserSession(db)

      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()

      await(dao.create("Local", "http://localhost:5000/",
          Hipache.Backend("localhost", 8080, "https")))

      val nonEmptyResponse = controller.list(session.newRequest)
      status(nonEmptyResponse) must_== 200
      val json = contentAsJson(nonEmptyResponse).as[List[JsObject]]
      json must haveSize(1)
      json.head \ "id" must beAnInstanceOf[JsString]
      json.head \ "name" must_== JsString("Local")
    }

  }
}
