package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import play.api.libs.ws.WS
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class UserDAOSpec extends PlaySpecification with SpecUtils {

  "UserDAO" should {

    "create a user from an identity" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db)
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
        val couchResponse = await(WS.url(s"${db.baseURL}/${user.id}").get)
        couchResponse.status must_== 200
        (couchResponse.json \ "type").as[String] must_== "User"
        (couchResponse.json \ "_id").as[String] must_== user.id
        (couchResponse.json \ "name").as[Option[String]] must_== user.name
        (couchResponse.json \ "email").as[Option[String]] must_== user.email
        (couchResponse.json \ "identities").as[Seq[String]] must_== user.identities
      }
      done
    }

    "lookup using identity" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db)
      val identity = MockIdentity("foo:bar", None, None)
      await(dao.findWith(identity)) must beNone
      val user = await(dao.createWith(identity))
      await(dao.findWith(identity)) must beSome
    }

    "get by ID" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db)
      val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))
      await(dao.get(user.id)) must beSome
    }

    "update name & email" in new WithApplication(fakeApp) {
      val dao = new UserDAO(db)
      val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))

      val update = user.update
        .withName(Some("Foo Bar"))
        .withEmail(Some("foo@bar.test"))

      val updatedUser = await(update.execIfDifferent(user))
      updatedUser.name must beSome
      updatedUser.email must beSome
    }

  }

}