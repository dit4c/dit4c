package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.libs.json._
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class AccessTokenDAOSpec extends PlaySpecification with SpecUtils {

  "AccessTokenDAO" should {

    "create a token" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db(app))
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      token.accessType must_== AccessType.Share
      // Check database has data
      val couchResponse =
        await(db(app).asSohvaDb.getDocById[JsValue](token.id, None))
      couchResponse must beSome
      val json = couchResponse.get
      (json \ "type").as[String] must_== "AccessToken"
      (json \ "_id").as[String] must_== token.id
      (json \ "_rev").as[String] must_== token._rev.get
      (json \ "code").as[String] must beMatching("[A-HJ-NP-Z2-9]{12}")
      (json \ "accessType").as[String] must_== AccessType.Share.toString
      (json \ "resource" \ "id").as[String] must_== computeNode.id
      (json \ "resource" \ "type").as[String] must_==
        ResourceType.ComputeNode.toString
    }

    "get by ID" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db(app))
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      // Refresh from DAO
      val refreshedToken = await(dao.get(token.id)).get
      refreshedToken.id must_== token.id
      refreshedToken._rev must beSome(token._rev.get)
      refreshedToken.accessType must_== token.accessType
      refreshedToken.resource.id must_== computeNode.id
    }

    "list by resource" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db(app))
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      val tokens = await(dao.listFor(computeNode))
      tokens must haveSize(1)
      tokens must contain(token)
    }

    "delete" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db(app))
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      await(dao.get(token.id)) must beSome
      await(token.delete)
      await(dao.get(token.id)) must beNone
    }

  }

  def mockComputeNode = {
    val randomId = UUID.randomUUID.toString
    new ComputeNode {
      override def id = randomId
      override def _rev = ???

      // Members declared in models.ComputeNode
      override def backend = ???
      override def containers = ???
      override def managementUrl = ???
      override def name = ???
      override def serverId = ???

      // Members declared in models.OwnableModel
      override def ownerIDs = ???

      // Members declared in models.UsableModel
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

}