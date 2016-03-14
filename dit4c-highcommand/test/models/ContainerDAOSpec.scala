package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import play.api.libs.json._
import providers.db.CouchDB
import java.util.Collections.EmptySet
import utils.SpecUtils
import play.api.test.WithApplication
import providers.RoutingMapEmitter

@RunWith(classOf[JUnitRunner])
class ContainerDAOSpec extends PlaySpecification with SpecUtils {

  import scala.concurrent.ExecutionContext.Implicits.global

  val dummyImage = "testimage"

  "ContainerDAO" should {

    "create a container from a name and description" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val dao = new ContainerDAO(db(app))
      Seq(
        ("test", ""),
        ("test", "A test description.")
      ).map { case (name, desc) =>
        val cn = MockComputeNode("mockcontainerid")
        val container = await(dao.create(session.user, name, dummyImage, cn))
        container.name must be(container.name)
        container.image must be(container.image)
        // Check database has data
        val couchResponse =
          await(db(app).asSohvaDb.getDocById[JsValue](container.id, None))
        couchResponse must beSome
        val json = couchResponse.get
        (json \ "type").as[String].must_==("Container") and
        (json \ "_id").as[String].must_==(container.id) and
        (json \ "_rev").asOpt[String].must_==(container._rev) and
        (json \ "name").as[String].must_==(container.name) and
        (json \ "image").as[String].must_==(container.image) and
        (json \ "computeNodeId").as[String].must_==(container.computeNodeId) and
        (json \ "ownerID").as[String].must_==(session.user.id)
      }.reduce(_ and _)
    }

    "get by ID" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      val dao = new ContainerDAO(db(app))
      val cn = MockComputeNode("mockcontainerid")
      val container = await(dao.create(
          session.user, "test1", dummyImage, cn))
      await(dao.get(container.id)) must beSome
    }

    "delete containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db(app))
      def getId(id: String) =
        await(db(app).asSohvaDb.getDocById[JsValue](container.id, None))
      val dao = new ContainerDAO(db(app))
      val cn = MockComputeNode("mockcontainerid")
      val container = await(dao.create(
          session.user, "test1", dummyImage, cn))
      getId(container.id) must beSome
      await(container.delete)
      getId(container.id) must beNone
    }

  }

  case class MockComputeNode(val id: String) extends ComputeNode {
    override def _rev: Option[String] = ???
    override def backend: RoutingMapEmitter.Backend = ???
    override def containers: providers.machineshop.ContainerProvider = ???
    override def managementUrl: String = ???
    override def name: String = ???
    override def serverId = ???
    override def ownerIDs = ???
    override def userIDs = ???

    // Members declared in models.UpdatableModel
    override def update = ???

    override def addOwner(user: User) = ???
    override def addUser(user: User) = ???
    override def removeOwner(userId: String) = ???
    override def removeUser(userId: String) = ???
    override def delete = ???
  }

}