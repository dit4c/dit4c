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
import providers.InjectorPlugin
import scala.concurrent.Future
import play.api.mvc.AnyContentAsEmpty
import utils.SpecUtils
import providers.RoutingMapEmitter

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ComputeNodeControllerSpec extends PlaySpecification with SpecUtils {
  import play.api.Play.current

  val testImage = "dit4c/dit4c-container-ipython"

  "ComputeNodeController" should {

    "list compute nodes" in new WithApplication(fakeApp) {
      val dao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))

      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()

      await(dao.create(
          session.user, "Local", "fakeid", "http://localhost:5000/",
          RoutingMapEmitter.Backend("localhost", 8080, "https")))

      val nonEmptyResponse = controller.list(session.newRequest)
      status(nonEmptyResponse) must_== 200
      val json = contentAsJson(nonEmptyResponse).as[List[JsObject]]
      json must haveSize(1)
      json match {
        case Seq(json) =>
          (json \ "id").as[String] must beAnInstanceOf[String]
          (json \ "name").as[String] must_== "Local"
          (json \ "managementUrl").as[String] must_== "http://localhost:5000/"
          (json \ "backend" \ "host").as[String] must_== "localhost"
          (json \ "backend" \ "port").as[Int] must_== 8080
          (json \ "backend" \ "scheme").as[String] must_== "https"
          (json \ "owned").as[Boolean] must beTrue
          (json \ "usable").as[Boolean] must beTrue
      }
    }

    "redeem access token" in new WithApplication(fakeApp) {
      val atDao = injector(app).instanceOf(classOf[AccessTokenDAO])
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val creatingSession = new UserSession(db(app), MockIdentity(
        "testing:creator", Some("Creator"), None
      ))
      val redeemingSession = new UserSession(db(app), MockIdentity(
        "testing:redeemer", Some("Redeemer"), None
      ))
      val cn = await(cnDao.create(creatingSession.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))
      val token = await(atDao.create(AccessToken.AccessType.Share, cn))

      cn.userIDs must contain(creatingSession.user.id)
      cn.userIDs must not contain(redeemingSession.user.id)

      val redemptionResponse = controller.redeemToken(
          cn.id,
          token.code
          )(redeemingSession.newRequest)
      status(redemptionResponse) must_== 303
      redirectLocation(redemptionResponse) must beSome(
        routes.ContainerController.index.url)

      val updatedCN = await(cnDao.get(cn.id)).get
      updatedCN.userIDs must contain(creatingSession.user.id)
      updatedCN.userIDs must contain(redeemingSession.user.id)
    }

    "create access token" in new WithApplication(fakeApp) {
      val atDao = injector(app).instanceOf(classOf[AccessTokenDAO])
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))

      val body: JsValue = Json.obj("type" -> "share")
      val req = session.newRequest.withBody(body)
      val response = controller.createToken(cn.id)(req)
      status(response) must_== 201

      val token = await(atDao.listFor(cn)).head
      token.accessType must be(AccessToken.AccessType.Share)

      val json = contentAsJson(response).as[JsObject]
      (json \ "code").as[String] must_== token.code
      (json \ "type").as[String] must_== "share"
    }

    "list access tokens" in new WithApplication(fakeApp) {
      val atDao = injector(app).instanceOf(classOf[AccessTokenDAO])
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))

      val emptyResponse = controller.listTokens(cn.id)(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()

      val token = await(atDao.create(AccessToken.AccessType.Share, cn))

      val nonEmptyResponse = controller.listTokens(cn.id)(session.newRequest)
      status(nonEmptyResponse) must_== 200
      val json = contentAsJson(nonEmptyResponse).as[List[JsObject]]
      json must haveSize(1)
      json match {
        case Seq(json) =>
          (json \ "code").as[String] must_== token.code
          (json \ "type").as[String] must_== "share"
      }
    }

    "list owners" in new WithApplication(fakeApp) {
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))

      val response = controller.listOwners(cn.id)(session.newRequest)
      status(response) must_== 200
      val json = contentAsJson(response).as[List[JsObject]]
      json must haveSize(1)
      json match {
        case Seq(json) =>
          val user = session.user
          (json \ "id").as[String] must_== user.id
          (json \ "name").asOpt[String] must_== user.name
          (json \ "email").asOpt[String] must_== user.email
      }
    }

    "remove owner" in new WithApplication(fakeApp) {
      val userDao = injector(app).instanceOf(classOf[UserDAO])
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val newOwner = await(userDao.createWith(MockIdentity(
        "test:new-owner",
        Some("Tommy Atkins"),
        Some("t.atkins@example.test")
      )))
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))

      // Should not be able to remove last owner
      val forbiddenResponse =
        controller.removeOwner(cn.id, session.user.id)(session.newRequest)
      status(forbiddenResponse) must_== 403

      await(cn.addOwner(newOwner))

      val okResponse =
        controller.removeOwner(cn.id, session.user.id)(session.newRequest)
      status(okResponse) must_== 204

      val updatedCn = await(cnDao.get(cn.id)).get
      updatedCn.ownerIDs must not contain(session.user.id)
      updatedCn.userIDs must contain(session.user.id)
    }

    "list users" in new WithApplication(fakeApp) {
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          RoutingMapEmitter.Backend("example.test", 80, "https")))

      val response = controller.listUsers(cn.id)(session.newRequest)
      status(response) must_== 200
      val json = contentAsJson(response).as[List[JsObject]]
      json must haveSize(1)
      json match {
        case Seq(json) =>
          val user = session.user
          (json \ "id").as[String] must_== user.id
          (json \ "name").asOpt[String] must_== user.name
          (json \ "email").asOpt[String] must_== user.email
      }
    }

    "remove user" in new WithApplication(fakeApp) {
      val userDao = injector(app).instanceOf(classOf[UserDAO])
      val cnDao = injector(app).instanceOf(classOf[ComputeNodeDAO])
      val controller = injector(app).instanceOf(classOf[ComputeNodeController])
      val session = new UserSession(db(app))
      val newUser = await(userDao.createWith(MockIdentity(
        "test:new-user",
        Some("Tommy Atkins"),
        Some("t.atkins@example.test")
      )))
      val cn = await(
        for {
          cn <- cnDao.create(session.user,
            "test", "fakeid", "http://example.test/",
            RoutingMapEmitter.Backend("example.test", 80, "https"))
          cnWithUser <- cn.addUser(newUser)
        } yield cnWithUser
      )

      cn.userIDs must contain(newUser.id)

      val okResponse =
        controller.removeUser(cn.id, newUser.id)(session.newRequest)
      status(okResponse) must_== 204

      val updatedCn = await(cnDao.get(cn.id)).get
      updatedCn.userIDs must not contain(newUser.id)
    }

  }
}
