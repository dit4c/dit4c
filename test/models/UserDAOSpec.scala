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
      val user = await(dao.createWith(MockIdentity("foo:bar")))
      user.identities must contain("foo:bar")
      // Check database has data
      val couchResponse = await(WS.url(s"${db.baseURL}/${user._id}").get)
      couchResponse.status must_== 200
      (couchResponse.json \ "_id").as[String] must_== user._id
      (couchResponse.json \ "identities").as[Seq[String]] must_== user.identities
    }

    "lookup using identity" in withDB { db =>
      val dao = new UserDAO(db)
      await(dao.findWith(MockIdentity("foo:bar"))) must beNone
      val user = await(dao.createWith(MockIdentity("foo:bar")))
      await(dao.findWith(MockIdentity("foo:bar"))) must beSome
    }

    "get by ID" in withDB { db =>
      val dao = new UserDAO(db)
      val user = await(dao.createWith(MockIdentity("foo:bar")))
      await(dao.get(user._id)) must beSome
    }

  }


  case class MockIdentity(uniqueId: String) extends Identity

}