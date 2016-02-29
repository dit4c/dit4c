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
import providers.machineshop.MachineShop
import providers.RoutingMapEmitter
import providers.ContainerResolver

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ContainerControllerSpec extends PlaySpecification with SpecUtils {
  import play.api.Play.current
  
  val testImage = "dit4c/dit4c-container-ipython"

  "ContainerController" should {

    "provide JSON list of containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val otherSession = new UserSession(db(app),MockIdentity("testing:other-user", Some("Other User"), None))
      val controller = getMockedController(app)
      val computeNodeDao = new ComputeNodeDAO(db(app), new KeyDAO(db(app)))
      val containerDao = new ContainerDAO(db(app))
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()
      val computeNode = 
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val containers = Seq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )
      await(containerDao.create(otherSession.user, "name4", testImage, computeNode))
      val threeResponse = controller.list(session.newRequest)
      status(threeResponse) must_== 200
      val jsObjs = contentAsJson(threeResponse).as[Seq[JsObject]]
      jsObjs must haveSize(3)
      containers.zip(jsObjs).foreach { case (container, json) =>
        (json \ "id").as[String] must_== container.id
        (json \ "name").as[String] must_== container.name
        // List no longer checks state of container on compute node for performance reasons
      }
      header("Cache-Control", threeResponse) must beSome("private, must-revalidate")
    }

    "provide JSON of a single container" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val controller = getMockedController(app)
      val computeNodeDao = new ComputeNodeDAO(db(app), new KeyDAO(db(app)))
      val containerDao = new ContainerDAO(db(app))
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val container =
        await(containerDao.create(session.user, "name1", testImage, computeNode))
      val response = controller.get(container.id)(session.newRequest)
      status(response) must_== 200
      val json = contentAsJson(response).as[JsObject]
      (json \ "id").as[String] must_== container.id
      (json \ "name").as[String] must_== container.name
      (json \ "active").as[Boolean] must beFalse
      header("Cache-Control", response) must beSome("private, must-revalidate")
    }

    "provide redirects for containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val controller = getMockedController(app)
      val keyDao = new KeyDAO(db(app))
      val key = keyDao.create("localhost.localdomain",512)
      val computeNodeDao = new ComputeNodeDAO(db(app), keyDao)
      val containerDao = new ContainerDAO(db(app))
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val container =
        await(containerDao.create(session.user, "test", testImage, computeNode))
      val response = controller.redirect(container.id)(session.newRequest)
      status(response) must_== 307
      header("Location", response) must beSome
      header("Location", response).get must endWith(".localhost.localdomain/")
      header("Cache-Control", response) must beSome("private, must-revalidate")
    }

    "create containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val controller = getMockedController(app)
      val keyDao = new KeyDAO(db(app))
      val key = keyDao.create("localhost.localdomain",512)
      val computeNodeDao = new ComputeNodeDAO(db(app), keyDao)
      val containerDao = new ContainerDAO(db(app))
      val computeNode = 
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val badRequestResponse  = 
        controller.create(session.newRequest[JsValue](Json.obj(
          "name"->"",
          "image" -> "test",
          "computeNodeId"->computeNode.id,
          "active"->true))) 
      status(badRequestResponse) must_== 400
      val okResponse  = 
        controller.create(session.newRequest[JsValue](Json.obj(
          "name"->"test",
          "image" -> "test",
          "computeNodeId"->computeNode.id,
          "active"->true))) 
      status(okResponse) must_== 201
      header("Cache-Control", okResponse) must beSome("private, must-revalidate")
    }
    
    "update containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val controller = getMockedController(app)
      val computeNodeDao = new ComputeNodeDAO(db(app), new KeyDAO(db(app)))
      val containerDao = new ContainerDAO(db(app))
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val container =
        await(containerDao.create(session.user, "test", testImage, computeNode))
      val response  = 
        controller.update(container.id)(session.newRequest[JsValue](Json.obj(
          "name" -> "changed",
          "image" -> "test",
          "computeNodeId" -> computeNode.id,
          "active" -> true))) 
      status(response) must_== 200
      val json = contentAsJson(response).as[JsObject]
      (json \ "id").as[String] must_== container.id
      (json \ "name").as[String] must_== "changed"
      (json \ "active").as[Boolean] must beTrue
      header("Cache-Control", response) must beSome("private, must-revalidate")
    }

  }

  def getMockedController(app: play.api.Application) = {
    new ContainerController(
        db(app),
        injector(app).instanceOf(classOf[Application]),
        injector(app).instanceOf(classOf[ContainerResolver])) {
      override def createComputeNodeContainer(container: Container) =
        Future.successful(MockMCC(container.name, false))

      override def resolveComputeNodeContainer(container: Container) =
        Future.successful(Some(MockMCC(container.name, false)))
    }
  }

  case class MockMCC(val name: String, val active: Boolean)
    extends MachineShop.Container {
    import Future.successful
    override def delete = successful[Unit](Unit)
    override def start = successful(MockMCC(name, true))
    override def stop = successful(MockMCC(name, false))
  }
}
