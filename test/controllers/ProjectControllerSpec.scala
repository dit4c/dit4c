package controllers

import java.util.UUID
import scala.concurrent.ExecutionContext
import org.junit.runner.RunWith
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import org.specs2.runner.JUnitRunner
import models._
import providers.auth.Identity
import play.api.test.WithApplication
import play.api.Play
import providers.InjectorPlugin
import scala.concurrent.Future
import play.api.mvc.AnyContentAsEmpty
import utils.SpecUtils

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ProjectControllerSpec extends PlaySpecification with SpecUtils {
  import play.api.Play.current
  
  val testImage = "dit4c/python"

  "ProjectController" should {

    "provide JSON list of projects" in new WithApplication(fakeApp) {
      val db = injector.getInstance(classOf[CouchDB.Database])
      val session = new UserSession(db)
      val controller = getMockedController
      val projectDao = new ProjectDAO(db)
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      (contentAsJson(emptyResponse) \ "project") must_== JsArray()
      val projects = Seq(
        await(projectDao.create(session.user, "name1", "desc1", testImage)),
        await(projectDao.create(session.user, "name2", "desc2", testImage)),
        await(projectDao.create(session.user, "name3", "desc3", testImage))
      )
      val threeResponse = controller.list(session.newRequest)
      status(threeResponse) must_== 200
      val jsObjs = (contentAsJson(threeResponse) \ "project").as[Seq[JsObject]]
      jsObjs must haveSize(3)
      projects.zip(jsObjs).foreach { case (project, json) =>
        (json \ "id").as[String] must_== project.id
        (json \ "name").as[String] must_== project.name
        (json \ "description").as[String] must_== project.description
        (json \ "active").as[Boolean] must beFalse
      }
    }

    "check names for new projects" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val controller = getMockedController;
      // Check with valid name
      {
        val response = controller.checkNewName("test")(session.newRequest)
        status(response) must_== 200
        (contentAsJson(response) \ "valid").as[Boolean] must beTrue
      }
      // Check with invalid, but unused names
      Seq("", "a"*64, "not_valid", "-prefix", "suffix-", "1337").foreach { n =>
        val response = controller.checkNewName(n)(session.newRequest)
        status(response) must_== 200
        val json = contentAsJson(response)
        (json \ "valid").as[Boolean] must beFalse
        (json \ "reason").as[String] must not beEmpty
      }
      // Check with a used name
      val projectDao = new ProjectDAO(db)
      await(projectDao.create(session.user, "test", "", testImage));
      {
        val response = controller.checkNewName("test")(session.newRequest)
        status(response) must_== 200
        val json = contentAsJson(response)
        (json \ "valid").as[Boolean] must beFalse
        (json \ "reason").as[String] must beMatching(".*exists.*")
      }
      done
    }

    def getMockedController = {
      val db = injector.getInstance(classOf[CouchDB.Database])
      new ProjectController(
          db,
          new ComputeNodeProjectHelper {
            override def creator = { project =>
              Future.successful(MockCNP(project.name, false))
            }
            override def resolver = { project =>
              Future.successful(Some(MockCNP(project.name, false)))
            }
          },
          injector.getInstance(classOf[Application]))
    }

  }

  case class MockCNP(val name: String, val active: Boolean)
    extends ComputeNode.Project {
    import Future.successful
    override def delete = successful[Unit](Unit)
    override def start = successful(MockCNP(name, true))
    override def stop = successful(MockCNP(name, false))
  }
}
