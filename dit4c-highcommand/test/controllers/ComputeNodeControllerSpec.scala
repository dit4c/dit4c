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
import providers.hipache.Hipache.Backend
import providers.hipache.Hipache

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

    "provide JSON list of compute nodes" in new WithApplication(fakeApp) {
      val dao = injector.getInstance(classOf[ComputeNodeDAO])
      val controller = injector.getInstance(classOf[ComputeNodeController])
      val session = new UserSession(db)

      val emptyResponse = controller.list(session.newRequest)
      status(emptyResponse) must_== 200
      contentAsJson(emptyResponse) must_== JsArray()

      await(dao.create(
          session.user, "Local", "fakeid", "http://localhost:5000/",
          Hipache.Backend("localhost", 8080, "https")))

      val nonEmptyResponse = controller.list(session.newRequest)
      status(nonEmptyResponse) must_== 200
      val json = contentAsJson(nonEmptyResponse).as[List[JsObject]]
      json must haveSize(1)
      json match {
        case Seq(json) =>
          json \ "id" must beAnInstanceOf[JsString]
          json \ "name" must_== JsString("Local")
          json \ "managementUrl" must_== JsString("http://localhost:5000/")
          json \ "backend" \ "host" must_== JsString("localhost")
          json \ "backend" \ "port" must_== JsNumber(8080)
          json \ "backend" \ "scheme" must_== JsString("https")
          json \ "owned" must_== JsBoolean(true)
          json \ "usable" must_== JsBoolean(true)
      }
    }

    "redeem an access token" in new WithApplication(fakeApp) {
      val atDao = injector.getInstance(classOf[AccessTokenDAO])
      val cnDao = injector.getInstance(classOf[ComputeNodeDAO])
      val controller = injector.getInstance(classOf[ComputeNodeController])
      val creatingSession = new UserSession(db, MockIdentity(
        "testing:creator", Some("Creator"), None
      ))
      val redeemingSession = new UserSession(db, MockIdentity(
        "testing:redeemer", Some("Redeemer"), None
      ))
      val cn = await(cnDao.create(creatingSession.user,
          "test", "fakeid", "http://example.test/",
          Hipache.Backend("example.test", 80, "https")))
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

    "create an access token" in new WithApplication(fakeApp) {
      val atDao = injector.getInstance(classOf[AccessTokenDAO])
      val cnDao = injector.getInstance(classOf[ComputeNodeDAO])
      val controller = injector.getInstance(classOf[ComputeNodeController])
      val session = new UserSession(db)
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          Hipache.Backend("example.test", 80, "https")))

      val body: JsValue = Json.obj("type" -> "share")
      val req = session.newRequest.withBody(body)
      val response = controller.createToken(cn.id)(req)
      status(response) must_== 201

      val token = await(atDao.listFor(cn)).head
      token.accessType must be(AccessToken.AccessType.Share)

      val json = contentAsJson(response).as[JsObject]
      json \ "code" must_== JsString(token.code)
      json \ "type" must_== JsString("share")
    }

    "provide JSON list of access tokens" in new WithApplication(fakeApp) {
      val atDao = injector.getInstance(classOf[AccessTokenDAO])
      val cnDao = injector.getInstance(classOf[ComputeNodeDAO])
      val controller = injector.getInstance(classOf[ComputeNodeController])
      val session = new UserSession(db)
      val cn = await(cnDao.create(session.user,
          "test", "fakeid", "http://example.test/",
          Hipache.Backend("example.test", 80, "https")))

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
          json \ "code" must_== JsString(token.code)
          json \ "type" must_== JsString("share")
      }
    }

  }
}
