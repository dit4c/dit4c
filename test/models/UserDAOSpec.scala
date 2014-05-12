package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity

@RunWith(classOf[JUnitRunner])
class UserDAOSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  lazy val serverInstance = new EphemeralCouchDBInstance
  def newDB =
    await(serverInstance.databases.create("db-"+UUID.randomUUID.toString))

  "UserDAO" should {

    "create a user from an identity" in {
      val dao = new UserDAO(newDB)
      val user = await(dao.createWith(MockIdentity("foo:bar")))
      user.identities must contain("foo:bar")
    }

  }


  case class MockIdentity(uniqueId: String) extends Identity

}