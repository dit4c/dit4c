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

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class UserControllerSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  import testing.TestUtils.fakeApp

  "UserController" should {

    "provides JSON of current user" in new WithApplication(fakeApp) {
      val controller = injector.getInstance(classOf[UserController])

      val withoutLogin = controller.currentUser(FakeRequest())
      status(withoutLogin) must_== 404

      val user = {
        val dao = new UserDAO(injector.getInstance(classOf[CouchDB.Database]))
        await(dao.createWith(new Identity() {
          def uniqueId = "test:testuser"
          def name = Some("Test User")
          def emailAddress = Some("user@example.test")
        }))
      }
      val withLogin = controller.currentUser(
          FakeRequest().withSession("userId" -> user._id))
      status(withLogin) must_== 200
      contentAsJson(withLogin) must_== Json.obj(
        "id" -> user._id,
        "name" -> user.name.get,
        "email" -> user.email.get
      )
    }

  }

  def injector(implicit app: play.api.Application) =
    Play.current.plugin(classOf[InjectorPlugin]).get.injector.get


}
