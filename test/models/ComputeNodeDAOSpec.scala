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
class ComputeNodeDAOSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  lazy val serverInstance = new EphemeralCouchDBInstance
  def withDB[A](f: CouchDB.Database => A): A =
    f(await(serverInstance.databases.create("db-"+UUID.randomUUID.toString)))

  "ComputeNodeDAO" should {

    "create a compute node" in withDB { db =>
      val dao = new ComputeNodeDAO(db)
      val node = await(dao.create("Local", "http://localhost:8080/"))
      node.name must_== "Local"
      node.url must_== "http://localhost:8080/"
      // Check database has data
      val couchResponse = await(WS.url(s"${db.baseURL}/${node._id}").get)
      couchResponse.status must_== 200
      (couchResponse.json \ "type").as[String] must_== "ComputeNode"
      (couchResponse.json \ "_id").as[String] must_== node._id
      (couchResponse.json \ "name").as[String] must_== node.name
      (couchResponse.json \ "url").as[String] must_== node.url
    }

    "list all compute nodes" in withDB { db =>
      val dao = new ComputeNodeDAO(db)
      await(dao.list) must beEmpty
      val node = await(dao.create("Local", "http://localhost:8080/"))
      await(dao.list) must contain(node)
    }

  }

}