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

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ProjectControllerSpec extends PlaySpecification {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  import testing.TestUtils.fakeApp

  "ProjectController" should {

    "provide JSON list of projects" in new WithApplication(fakeApp) {
      val db = injector.getInstance(classOf[CouchDB.Database])
      val controller = new ProjectController(
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
      val projectDao = new ProjectDAO(db)
      val emptyResponse = controller.list(FakeRequest())
      status(emptyResponse) must_== 200
      (contentAsJson(emptyResponse) \ "project") must_== JsArray()
      val projects = await(Future.sequence(Seq(
        projectDao.create("name1", "desc1"),
        projectDao.create("name2", "desc2"),
        projectDao.create("name3", "desc3")
      )))
      val threeResponse = controller.list(FakeRequest())
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

  }

  def injector(implicit app: play.api.Application) =
    Play.current.plugin(classOf[InjectorPlugin]).get.injector.get

  case class MockCNP(val name: String, val active: Boolean)
    extends ComputeNode.Project {
    import Future.successful
    override def delete = successful()
    override def start = successful(MockCNP(name, true))
    override def stop = successful(MockCNP(name, false))
  }
}
