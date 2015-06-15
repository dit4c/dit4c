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
import utils.SpecUtils
import providers.hipache.Hipache
import play.api.{Application => App}
import scala.util.Random
import scala.concurrent.Future

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class AuthControllerSpec extends PlaySpecification with SpecUtils {

  "AuthController" should {

    "provide JSON for public keys" in new WithApplication(fakeApp) {
      val controller = injector(app).instanceOf(classOf[AuthController])

      val response = controller.publicKeys(FakeRequest())
      status(response) must_== 200
      val json = contentAsJson(response)
      (json \ "keys").as[Seq[JsValue]].foreach { key =>
        (key \ "kty").as[String] must_== "RSA"
        (key \ "n").asOpt[String] must beSome[String]
        (key \ "e").asOpt[String] must beSome[String]
        (key \ "d").asOpt[String] must beNone
      }
    }

    "allow account mergers to be cancelled" in new WithApplication(fakeApp) {
      val controller = injector(app).instanceOf(classOf[AuthController])

      val identity1 = new Identity() {
        def uniqueId = "test:testuser1"
        def name = Some("Test User 1")
        def emailAddress = Some("user1@example.test")
      }
      val identity2 = new Identity() {
        def uniqueId = "test:testuser2"
        def name = Some("Test User 2")
        def emailAddress = Some("user2@example.test")
      }
      val (primaryUser, primaryContainers) = 
        await(createHierarchy(identity1))
      val (secondaryUser, secondaryContainers) = 
        await(createHierarchy(identity2))
      val response = controller.cancelMerge(
          (new UserSession(db(app), identity1)).newRequest
            .withSession("mergeUserId" -> secondaryUser.id))
      session(response).get("mergeUserId") must beNone
      redirectLocation(response) must
        beSome(controllers.routes.Application.main("account").url)
    }

    "merge accounts" in new WithApplication(fakeApp) {
      val controller = injector(app).instanceOf(classOf[AuthController])
      val computeNodeDao = new ComputeNodeDAO(db(app), new KeyDAO(db(app)))
      val containerDao = new ContainerDAO(db(app))
      val userDao = new UserDAO(db(app))

      val identity1 = new Identity() {
        def uniqueId = "test:testuser1"
        def name = Some("Test User 1")
        def emailAddress = Some("user1@example.test")
      }
      val identity2 = new Identity() {
        def uniqueId = "test:testuser2"
        def name = Some("Test User 2")
        def emailAddress = Some("user2@example.test")
      }
      val (primaryUser, primaryContainers) = 
        await(createHierarchy(identity1))
      val (secondaryUser, secondaryContainers) = 
        await(createHierarchy(identity2))

      val response = controller.confirmMerge(
          (new UserSession(db(app), identity1)).newRequest
            .withSession("mergeUserId" -> secondaryUser.id))
      status(response) must_== 200
      session(response).get("mergeUserId") must beNone
      (contentAsJson(response) \ "id").as[String] must_== primaryUser.id
      val updatedUser = await(userDao.get(primaryUser.id)).get
      updatedUser.identities must contain(atLeast(primaryUser.identities: _*))
      updatedUser.identities must contain(atLeast(secondaryUser.identities: _*))
      val allContainerIDs =
        (primaryContainers ++ secondaryContainers).map(_.id)
      val allComputeNodeIDs =
        (primaryContainers ++ secondaryContainers).map(_.computeNodeId).distinct
      await(containerDao.list)
        .filter(_.ownerIDs.contains(primaryUser.id))
        .map(_.id) must contain(allOf(allContainerIDs: _*))
      val computeNodes = await(computeNodeDao.list)
      computeNodes
        .filter(_.ownerIDs.contains(primaryUser.id))
        .map(_.id) must contain(allOf(allComputeNodeIDs: _*))
      computeNodes
        .filter(_.userIDs.contains(primaryUser.id))
        .map(_.id) must contain(allOf(allComputeNodeIDs: _*))
    }
  }

  val testImage = "dit4c/dit4c-container-ipython"

  def createHierarchy(
      identity: Identity)(implicit app: App): Future[(User, Seq[Container])] = {
    def randomAlpha = Random.alphanumeric.take(20).mkString

    for {
      user <- (new UserDAO(db(app))).createWith(identity)
      computeNodes <- Future.sequence {
        Stream.continually {
          val cnHostname = randomAlpha
          (new ComputeNodeDAO(db(app), new KeyDAO(db(app)))).create(
              user, cnHostname, s"id-$cnHostname", s"http://$cnHostname:8080/",
              Hipache.Backend(cnHostname, 80, "http"))
        }.take(Random.nextInt(3)+1)
      }
      containers <- Future.sequence {
        Stream.continually {
          val cn = Random.shuffle(computeNodes).head
          (new ContainerDAO(db(app))).create(user, randomAlpha, testImage, cn)
        }.take(Random.nextInt(19)+1)
      }
    } yield (user, containers)
  }
  
  
  
}
