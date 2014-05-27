package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import play.api.libs.ws.WS
import providers.db.CouchDB
import java.util.Collections.EmptySet

@RunWith(classOf[JUnitRunner])
class ProjectDAOSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  lazy val serverInstance = new EphemeralCouchDBInstance
  def withDB[A](f: CouchDB.Database => A): A =
    f(await(serverInstance.databases.create("db-"+UUID.randomUUID.toString)))

  "ProjectDAO" should {

    "create a project from a name and description" in withDB { db =>
      val dao = new ProjectDAO(db)
      Seq(
        ("test1", ""),
        ("test2", "A test description.")
      ).foreach { case (name, desc) =>
        val project = await(dao.create(name, desc))
        project.name must be(project.name)
        project.description must be(project.description)
        // Check database has data
        val couchResponse = await(WS.url(s"${db.baseURL}/${project.id}").get)
        couchResponse.status must_== 200
        (couchResponse.json \ "type").as[String] must_== "Project"
        (couchResponse.json \ "_id").as[String] must_== project.id
        (couchResponse.json \ "name").as[String] must_== project.name
        (couchResponse.json \ "description").as[String] must_== project.description
      }
      done
    }

    "get by ID" in withDB { db =>
      val dao = new ProjectDAO(db)
      val project = await(dao.create("test1", "A test description."))
      await(dao.get(project.id)) must beSome
    }

    "delete projects" in withDB { db =>
      def getId(id: String) = await(WS.url(s"${db.baseURL}/$id").get)
      val dao = new ProjectDAO(db)
      val project = await(dao.create("test1", "A test description."))
      getId(project.id).status must_== 200
      await(project.delete)
      getId(project.id).status must_== 404
    }

  }

}