package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import play.api.libs.ws.WS
import providers.db.CouchDB
import java.util.Collections.EmptySet

@RunWith(classOf[JUnitRunner])
class UserDAOSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  lazy val serverInstance = new EphemeralCouchDBInstance
  def withDB[A](f: CouchDB.Database => A): A =
    f(await(serverInstance.databases.create("db-"+UUID.randomUUID.toString)))

  "UserDAO" should {

    "create a user from an identity" in withDB { db =>
      val dao = new UserDAO(db)
      Seq(
        MockIdentity("test:user1", None, None),
        MockIdentity("test:user2", Some("Test User"), None),
        MockIdentity("test:user3", Some("Test User"), Some("user@example.test"))
      ).foreach { identity =>
        val user = await(dao.createWith(identity))
        user.name must be(identity.name)
        user.email must be(identity.emailAddress)
        user.identities must contain(identity.uniqueId)
        // Check database has data
        val couchResponse = await(WS.url(s"${db.baseURL}/${user._id}").get)
        couchResponse.status must_== 200
        (couchResponse.json \ "type").as[String] must_== "User"
        (couchResponse.json \ "_id").as[String] must_== user._id
        (couchResponse.json \ "name").as[Option[String]] must_== user.name
        (couchResponse.json \ "email").as[Option[String]] must_== user.email
        (couchResponse.json \ "identities").as[Seq[String]] must_== user.identities
        // Stored as sets, but should exist as lists
        (couchResponse.json \ "projects" \ "owned").as[Seq[String]] must_== Nil
        (couchResponse.json \ "projects" \ "shared").as[Seq[String]] must_== Nil
      }
      done
    }

    "lookup using identity" in withDB { db =>
      val dao = new UserDAO(db)
      val identity = MockIdentity("foo:bar", None, None)
      await(dao.findWith(identity)) must beNone
      val user = await(dao.createWith(identity))
      await(dao.findWith(identity)) must beSome
    }

    "get by ID" in withDB { db =>
      val dao = new UserDAO(db)
      val user = await(dao.createWith(MockIdentity("foo:bar", None, None)))
      await(dao.get(user._id)) must beSome
    }

  }


  case class MockIdentity(
      uniqueId: String,
      name: Option[String],
      emailAddress: Option[String]) extends Identity

}