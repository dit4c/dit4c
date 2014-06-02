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
import play.api.test.WithApplication
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class ComputeNodeDAOSpec extends PlaySpecification with SpecUtils {

  "ComputeNodeDAO" should {

    "create a compute node" in new WithApplication(fakeApp) {
      val dao = new ComputeNodeDAO(db)
      val node = await(dao.create("Local", "http://localhost:8080/"))
      node.name must_== "Local"
      node.url must_== "http://localhost:8080/"
      // Check database has data
      val couchResponse = await(WS.url(s"${db.baseURL}/${node.id}").get)
      couchResponse.status must_== 200
      (couchResponse.json \ "type").as[String] must_== "ComputeNode"
      (couchResponse.json \ "_id").as[String] must_== node.id
      (couchResponse.json \ "name").as[String] must_== node.name
      (couchResponse.json \ "url").as[String] must_== node.url
    }

    "list all compute nodes" in new WithApplication(fakeApp) {
      val dao = new ComputeNodeDAO(db)
      await(dao.list) must beEmpty
      val node = await(dao.create("Local", "http://localhost:8080/"))
      await(dao.list) must haveSize(1)
    }

  }

}