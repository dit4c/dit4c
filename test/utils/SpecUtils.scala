package utils

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import models.UserDAO
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import providers.auth.Identity
import providers.db.CouchDB

trait SpecUtils {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  object MockIdentity extends Identity {
    override val uniqueId = "testing:test-user"
    override val name = Some("Test User")
    override val emailAddress = None
  }

  class UserSession(db: CouchDB.Database, identity: Identity = MockIdentity) {
    val user = Await.result(
        (new UserDAO(db)).createWith(identity),
        Duration(20, "seconds"))

    def newRequest: FakeRequest[AnyContentAsEmpty.type] = {
      FakeRequest().withSession("userId" -> user.id)
    }
  }

}