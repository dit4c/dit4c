package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class UserDAOSpec extends PlaySpecification with SpecUtils {

  "UserDAO" should {

    "create a user" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      val identity = MockIdentity("test:foobar",
          Some("Foo Bar"), Some("foo@bar.net"))
      val user = await(dao.createWith(identity))
      (user._rev must beSome) and
      (user.identities must_== Set(identity.uniqueId)) and
      (user.name must_== identity.name) and
      (user.email must_== identity.emailAddress) and {
        // Check database has data
        val couchResponse =
          await(db(app).asSohvaDb.getDocById[JsValue](user.id, None))
        (couchResponse must beSome) and {
          val json = couchResponse.get
          ((json \ "type").as[String] must_== "User") and
          ((json \ "_id").as[String] must_== user.id) and
          ((json \ "_rev").asOpt[String] must_== user._rev)
          ((json \ "name").asOpt[String] must_== user.name) and
          ((json \ "email").asOpt[String] must_== user.email)
        }
      }
    }

    "get by ID" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      val identity = MockIdentity("test:foobar",
          Some("Foo Bar"), Some("foo@bar.net"))
      val user1 = await(dao.createWith(identity))
      val user2 = await(dao.get(user1.id))
      user2 must_== Some(user1)
    }
  }
}