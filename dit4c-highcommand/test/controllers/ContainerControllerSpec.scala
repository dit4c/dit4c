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
import providers.hipache.Hipache

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
      val db = injector.getInstance(classOf[CouchDB.Database])
      val session = new UserSession(db)
      val controller = getMockedController
      val computeNodeDao = new ComputeNodeDAO(db, new KeyDAO(db))
      val containerDao = new ContainerDAO(db)
      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()
      val computeNode = 
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            Hipache.Backend("localhost", 8080, "https")))
      val containers = Seq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )
      val threeResponse = controller.list(session.newRequest)
      status(threeResponse) must_== 200
      val jsObjs = contentAsJson(threeResponse).as[Seq[JsObject]]
      jsObjs must haveSize(3)
      containers.zip(jsObjs).foreach { case (container, json) =>
        (json \ "id").as[String] must_== container.id
        (json \ "name").as[String] must_== container.name
        (json \ "active").as[Boolean] must beFalse
      }
    }

    "create containers" in new WithApplication(fakeApp) {
      val db = injector.getInstance(classOf[CouchDB.Database])
      val session = new UserSession(db)
      val controller = getMockedController
      val keyDao = new KeyDAO(db)
      val key = keyDao.create("localhost.localdomain",512)
      val computeNodeDao = new ComputeNodeDAO(db, keyDao)
      val containerDao = new ContainerDAO(db)
      val computeNode = 
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            Hipache.Backend("localhost", 8080, "https")))
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
    }

    "check names for new containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val controller = getMockedController
      val computeNodeDao = new ComputeNodeDAO(db, new KeyDAO(db))
      val computeNode = 
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            Hipache.Backend("localhost", 8080, "https")));
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
      val containerDao = new ContainerDAO(db)
      await(containerDao.create(session.user, "test", testImage, computeNode));
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
      new ContainerController(
          db,
          new ComputeNodeContainerHelper {
            override def creator = { container =>
              Future.successful(MockMCC(container.name, false))
            }
            override def resolver = { container =>
              Future.successful(Some(MockMCC(container.name, false)))
            }
          },
          injector.getInstance(classOf[Application]))
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
