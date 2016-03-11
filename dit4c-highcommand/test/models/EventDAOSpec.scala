package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils
import java.time.Instant

@RunWith(classOf[JUnitRunner])
class EventDAOSpec extends PlaySpecification with SpecUtils {

  "EventDAO" should {

    "create a login event" in new WithApplication(fakeApp) {
      val dao = new EventDAO(db(app))
      val session = new UserSession(db(app))
      val event = await(dao.createLogin(
          session.user,
          MockIdentity(session.user.identities.head, None, None),
          Instant.now
      ))
      (event.userId must_== session.user.id) and
      (event.identity must_== session.user.identities.head) and
      (event.name must_== session.user.name) and
      (event.email must_== session.user.email) and
      {
        // Check database has data
        val couchResponse =
          await(db(app).asSohvaDb.getDocById[JsValue](event.id, None))
        (couchResponse must beSome)
        val json = couchResponse.get
        ((json \ "type").as[String] must_== "Event") and
        ((json \ "subtype").as[String] must_== "Login") and
        ((json \ "_id").as[String] must_== event.id) and
        ((json \ "_rev").asOpt[String] must_== event._rev)
        ((json \ "timestamp").as[String] must_== event.timestamp.toString) and
        ((json \ "name").asOpt[String] must_== event.name) and
        ((json \ "email").asOpt[String] must_== event.email)
      }
    }

    "get by ID" in new WithApplication(fakeApp) {
      val dao = new EventDAO(db(app))
      val session = new UserSession(db(app))
      val event1 = await(dao.createLogin(
          session.user,
          MockIdentity(session.user.identities.head, None, None),
          Instant.now
      ))
      val event2 = await(dao.get(event1.id))
      event2 must_== Some(event1)
    }
  }
}