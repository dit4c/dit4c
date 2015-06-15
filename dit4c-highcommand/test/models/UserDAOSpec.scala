package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import play.api.libs.json._
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class UserDAOSpec extends PlaySpecification with SpecUtils {

  "UserDAO" should {

    "create a user from an identity" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      Seq(
        MockIdentity("test:user1", None, None),
        MockIdentity("test:user2", Some("Test User"), None),
        MockIdentity("test:user3", Some("Test User"), Some("user@example.test"))
      ).foreach { identity =>
        val user = await(dao.createWith(identity))
        user.name must_== identity.name
        user.email must_== identity.emailAddress
        user.identities must contain(identity.uniqueId)
        // Check database has data
        val couchResponse =
          await(db(app).asSohvaDb.getDocById[JsValue](user.id, None))
        couchResponse must beSome
        val json = couchResponse.get
        (json \ "type").as[String] must_== "User"
        (json \ "_id").as[String] must_== user.id
        (json \ "name").asOpt[String] must_== user.name
        (json \ "email").asOpt[String] must_== user.email
        (json \ "identities").as[Seq[String]] must_== user.identities
      }
      done
    }

    "lookup using identity" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      val identity = MockIdentity("foo:bar", None, None)
      await(dao.findWith(identity)) must beNone
      val user = await(dao.createWith(identity))
      await(dao.findWith(identity)) must beSome
    }

    "get by ID" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))
      await(dao.get(user.id)) must beSome
    }

    "update name & email" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db(app))
      val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))

      val update = user.update
        .withName(Some("Foo Bar"))
        .withEmail(Some("foo@bar.test"))

      val updatedUser = await(update.execIfDifferent(user))
      updatedUser.name must beSome
      updatedUser.email must beSome
    }

    "create, merge or update" >> {

      "without current user" >> {

        "creates new user for unknown identities" in new WithApplication(fakeApp) {
          val dao = new UserDAO(db(app))
          val identity = MockIdentity("foo:bar", None, None)
          val resultingUser = await(dao.createMergeOrUpdate(None, identity))
          resultingUser.name must_== identity.name
          resultingUser.email must_== identity.emailAddress
          resultingUser.identities must contain(identity.uniqueId)
        }

        "updates existing user for known identities" in new WithApplication(fakeApp) {
          val dao = new UserDAO(db(app))
          val user =
            await(dao.createWith(MockIdentity("foo:bar", None, None)))
          val identity =
            MockIdentity("foo:bar", Some("Foo Bar"), Some("foo@bar.test"))
          val resultingUser = await(dao.createMergeOrUpdate(None, identity))
          resultingUser.id must_== user.id
          resultingUser.name must_== identity.name
          resultingUser.email must_== identity.emailAddress
          resultingUser.identities must contain(identity.uniqueId)
        }

      }
/*
      "with current user" >> {

        "updates user for unknown identities" in new WithApplication(fakeApp) {
          val dao = new UserDAO(db(app))
          val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))
          val identity =
            MockIdentity("foo:baz", Some("Foo Baz"), Some("foo@baz.test"))
          val resultingUser = await {
            dao.createMergeOrUpdate(Some(user), identity)
          }
          resultingUser.id must_== user.id
          resultingUser.name must_== identity.name
          resultingUser.email must_== identity.emailAddress
          user.identities.foreach { uniqueId =>
            resultingUser.identities must contain(uniqueId)
          }
          resultingUser.identities must contain(identity.uniqueId)
        }

        "updates user for known connected identities" in new WithApplication(fakeApp) {
          val dao = new UserDAO(db(app))
          val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))
          val identity =
            MockIdentity("foo:bar", Some("Foo Bar"), Some("foo@bar.test"))
          val resultingUser = await {
            dao.createMergeOrUpdate(Some(user), identity)
          }
          resultingUser.id must_== user.id
          resultingUser.name must_== identity.name
          resultingUser.email must_== identity.emailAddress
          resultingUser.identities must contain(identity.uniqueId)
          resultingUser.identities must haveSize(1)
        }

        "merges users for known unconnected identities" in new WithApplication(fakeApp) {
          val dao = new UserDAO(db(app))
          val user1 = await(dao.createWith(MockIdentity("foo:bar", None, None)))
          val user2 = await(dao.createWith(MockIdentity("foo:baz",
            Some("Foo Bar"), Some("foo@bar.test"))))
          val identity =
            MockIdentity("foo:baz", None, None)
          val resultingUser = await {
            dao.createMergeOrUpdate(Some(user1), identity)
          }
          resultingUser.id must_== user1.id
          resultingUser.name must_== user2.name
          resultingUser.email must_== user2.email
          resultingUser.identities must contain("foo:bar", "foo:baz")
          resultingUser.identities must haveSize(2)
        }
      }
*/
    }

  }

}
