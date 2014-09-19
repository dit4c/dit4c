package utils

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import models.UserDAO
import play.api.mvc.AnyContentAsEmpty
import play.api.test.{FakeHeaders, FakeRequest}
import providers.auth.Identity
import providers.db.CouchDB
import org.specs2.matcher.ConcurrentExecutionContext
import providers.InjectorPlugin

trait SpecUtils extends ConcurrentExecutionContext {

  def fakeApp = testing.TestUtils.fakeApp

  case class MockIdentity(
      uniqueId: String,
      name: Option[String],
      emailAddress: Option[String]) extends Identity

  private val mockIdentity =
    MockIdentity("testing:test-user", Some("Test User"), None)

  class UserSession(db: CouchDB.Database, identity: Identity = mockIdentity) {
    val user = Await.result(
      (new UserDAO(db)).createWith(identity),
      Duration(20, "seconds"))

    def newRequest: FakeRequest[AnyContentAsEmpty.type] = {
      FakeRequest().withSession("userId" -> user.id)
    }
    
    def newRequest[A](body: A): FakeRequest[A] = {
      FakeRequest[A]("GET", "", FakeHeaders(), body).withSession("userId" -> user.id)
    }
  }

  def db(implicit app: play.api.Application) =
    injector.getInstance(classOf[CouchDB.Database])

  def injector(implicit app: play.api.Application) =
    app.plugin(classOf[InjectorPlugin]).get.injector.get

  def createTestKey(implicit app: play.api.Application): models.Key =
    Await.result(
        (new models.KeyDAO(db)).create("DIT4C Test Key", 512),
        Duration(20, "seconds"))

}
