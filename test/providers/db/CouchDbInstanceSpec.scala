package providers.db
package providers.auth

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification

@RunWith(classOf[JUnitRunner])
class CouchDbInstanceSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  def createNewInstance = new EphemeralCouchDBInstance

  "CouchDbInstance" should {

    "get databases" in {
      val instance = createNewInstance
      await(instance.databases("_users")) must beSome
    }

    "create databases" in {
      val instance = createNewInstance
      await(instance.databases("test")) must beNone
      val db = await(instance.databases.create("test"))
      db.name must_== "test"
      await(instance.databases("test")) must beSome
    }

    "list databases" in {
      val instance = createNewInstance
      await(instance.databases.list).find(_.name == "_users") must beSome
      await(instance.databases.list).find(_.name == "test") must beNone
      val db = await(instance.databases.create("test"))
      db.name must_== "test"
      await(instance.databases.list).find(_.name == "test") must beSome
    }

  }


}