package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import providers.db.CouchDB
import play.api.test.WithApplication
import utils.SpecUtils
import providers.hipache.Hipache

@RunWith(classOf[JUnitRunner])
class ComputeNodeDAOSpec extends PlaySpecification with SpecUtils {

  "ComputeNodeDAO" should {

    "create a compute node" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ComputeNodeDAO(db, new KeyDAO(db))
      val node = await(dao.create(
          session.user,
          "Local",
          "fakeid",
          "http://localhost:8080/",
          Hipache.Backend("localhost", 6000)
      ))
      node.name must_== "Local"
      node.serverId must_== "fakeid"
      node.managementUrl must_== "http://localhost:8080/"
      node.backend must_== Hipache.Backend("localhost", 6000)
      // Check database has data
      val couchResponse =
        await(db.asSohvaDb.getDocById[JsValue](node.id, None))
      couchResponse must beSome
      val json = couchResponse.get
      (json \ "type").as[String] must_== "ComputeNode"
      (json \ "_id").as[String] must_== node.id
      (json \ "_rev").as[Option[String]] must_== node._rev
      (json \ "name").as[String] must_== node.name
      (json \ "serverID").as[String] must_== node.serverId
      (json \ "managementURL").as[String] must_== node.managementUrl
      (json \ "backend" \ "host").as[String] must_== "localhost"
      (json \ "backend" \ "port").as[Int] must_== 6000
      (json \ "backend" \ "scheme").as[String] must_== "http"
      (json \ "ownerIDs").as[Set[String]] must contain(session.user.id)
      (json \ "userIDs").as[Set[String]] must contain(session.user.id)
    }

    "list all compute nodes" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ComputeNodeDAO(db, new KeyDAO(db))
      await(dao.list) must beEmpty
      val node = await(dao.create(
          session.user,
          "Local",
          "fakeid",
          "http://localhost:8080/",
          Hipache.Backend("localhost", 6000)
      ))
      await(dao.list) must haveSize(1)
    }

  }

}