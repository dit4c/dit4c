package utils

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import org.specs2.matcher.ConcurrentExecutionContext
import com.google.inject.Inject
import models.UserDAO
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import providers.InjectorPlugin
import providers.auth.Identity
import providers.db.CouchDB
import providers.db.CouchDB.Database
import scala.concurrent.ExecutionContext

trait SpecUtils {
  import scala.languageFeature.implicitConversions

  def fakeApp = testing.TestUtils.fakeApp

  case class MockIdentity(
      uniqueId: String,
      name: Option[String],
      emailAddress: Option[String]) extends Identity

  private val mockIdentity =
    MockIdentity("testing:test-user", Some("Test User"), None)

  class UserSession(
      db: CouchDB.Database,
      identity: Identity = mockIdentity)(implicit ec: ExecutionContext) {
    val user = Await.result({
      val dao = new UserDAO(db)
      for {
        optUser <- dao.findWith(identity)
        user <- optUser
          .map(Future.successful(_))
          .getOrElse(dao.createWith(identity))
      } yield user
    }, Duration(20, "seconds"))

    def newRequest: FakeRequest[AnyContentAsEmpty.type] = {
      FakeRequest().withSession("userId" -> user.id)
    }

    def newRequest[A](body: A): FakeRequest[A] = {
      FakeRequest[A]("GET", "", FakeHeaders(), body).withSession("userId" -> user.id)
    }
  }

  def db(app: play.api.Application) =
    injector(app).instanceOf(classOf[CouchDB.Database])

  def injector(app: play.api.Application) = app.injector

  def createTestKey(
      app: play.api.Application)(implicit ec: ExecutionContext): models.Key =
    Await.result(
        (new models.KeyDAO(db(app))).create("DIT4C Test Key", 512),
        Duration(20, "seconds"))

}
