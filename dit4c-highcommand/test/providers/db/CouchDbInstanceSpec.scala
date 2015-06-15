package providers.db

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.Application
import play.api.test._
import akka.actor.ActorSystem

@RunWith(classOf[JUnitRunner])
class CouchDbInstanceSpec extends PlaySpecification {
  
  import testing.TestUtils.fakeApp

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  def createNewInstance(implicit app: Application) =
    new EphemeralCouchDBInstance()(ec, app.injector.instanceOf[ActorSystem])

  "CouchDbInstance" should {

    "get databases" in new WithApplication(fakeApp) {
      val instance = createNewInstance
      await(instance.databases("_users")) must beSome
    }

    "create databases" in new WithApplication(fakeApp) {
      val instance = createNewInstance
      await(instance.databases("test")) must beNone
      val db = await(instance.databases.create("test"))
      db.name must_== "test"
      await(instance.databases("test")) must beSome
    }

    "list databases" in new WithApplication(fakeApp) {
      val instance = createNewInstance
      await(instance.databases.list).find(_.name == "_users") must beSome
      await(instance.databases.list).find(_.name == "test") must beNone
      val db = await(instance.databases.create("test"))
      db.name must_== "test"
      await(instance.databases.list).find(_.name == "test") must beSome
    }

  }


}