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
import utils.SpecUtils
import play.api.test.WithApplication

@RunWith(classOf[JUnitRunner])
class ProjectDAOSpec extends PlaySpecification with SpecUtils {

  import testing.TestUtils.fakeApp

  "ProjectDAO" should {

    "create a project from a name and description" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ProjectDAO(db)
      Seq(
        ("test1", ""),
        ("test2", "A test description.")
      ).foreach { case (name, desc) =>
        val project = await(dao.create(session.user, name, desc))
        project.name must be(project.name)
        project.description must be(project.description)
        // Check database has data
        val cr = await(WS.url(s"${db.baseURL}/${project.id}").get)
        cr.status must_== 200
        (cr.json \ "type").as[String] must_== "Project"
        (cr.json \ "_id").as[String] must_== project.id
        (cr.json \ "name").as[String] must_== project.name
        (cr.json \ "description").as[String] must_== project.description
      }
      done
    }

    "get by ID" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ProjectDAO(db)
      val project = await(dao.create(
          session.user, "test1", "A test description."))
      await(dao.get(project.id)) must beSome
    }

    "delete projects" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      def getId(id: String) = await(WS.url(s"${db.baseURL}/$id").get)
      val dao = new ProjectDAO(db)
      val project = await(dao.create(
          session.user, "test1", "A test description."))
      getId(project.id).status must_== 200
      await(project.delete)
      getId(project.id).status must_== 404
    }

  }

}