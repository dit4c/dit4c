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
class ContainerDAOSpec extends PlaySpecification with SpecUtils {

  import testing.TestUtils.fakeApp

  val dummyImage = "testimage"

  "ContainerDAO" should {

    "create a container from a name and description" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ContainerDAO(db)
      Seq(
        ("test1", ""),
        ("test2", "A test description.")
      ).foreach { case (name, desc) =>
        val cn = MockComputeNode("mockcontainerid")
        val container = await(dao.create(session.user, name, dummyImage, cn))
        container.name must be(container.name)
        container.image must be(container.image)
        // Check database has data
        val cr = await(WS.url(s"${db.baseURL}/${container.id}").get)
        cr.status must_== 200
        (cr.json \ "type").as[String] must_== "Container"
        (cr.json \ "_id").as[String] must_== container.id
        (cr.json \ "name").as[String] must_== container.name
        (cr.json \ "image").as[String] must_== container.image
        (cr.json \ "computeNodeId").as[String] must_== container.computeNodeId
        (cr.json \ "ownerIDs").as[Set[String]] must contain(session.user.id)
      }
      done
    }

    "get by ID" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      val dao = new ContainerDAO(db)
      val cn = MockComputeNode("mockcontainerid")
      val container = await(dao.create(
          session.user, "test1", dummyImage, cn))
      await(dao.get(container.id)) must beSome
    }

    "delete containers" in new WithApplication(fakeApp) {
      val session = new UserSession(db)
      def getId(id: String) = await(WS.url(s"${db.baseURL}/$id").get)
      val dao = new ContainerDAO(db)
      val cn = MockComputeNode("mockcontainerid")
      val container = await(dao.create(
          session.user, "test1", dummyImage, cn))
      getId(container.id).status must_== 200
      await(container.delete)
      getId(container.id).status must_== 404
    }

    case class MockComputeNode(val id: String) extends ComputeNode {
      override def _rev: Option[String] = ???
      override def backend: providers.hipache.Hipache.Backend = ???
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
      override def delete = ???
    }

  }

}