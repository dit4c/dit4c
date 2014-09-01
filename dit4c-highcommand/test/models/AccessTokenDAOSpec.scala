package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test._
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import play.api.libs.ws.WS
import providers.db.CouchDB
import java.util.Collections.EmptySet
import providers.InjectorPlugin
import utils.SpecUtils

@RunWith(classOf[JUnitRunner])
class AccessTokenDAOSpec extends PlaySpecification with SpecUtils {

  "AccessTokenDAO" should {

    "create a token" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db)
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      token.accessType must_== AccessType.Share
      // Check database has data
      val couchResponse = await(WS.url(s"${db.baseURL}/${token.id}").get)
      couchResponse.status must_== 200
      val json = couchResponse.json
      (json \ "type").as[String] must_== "AccessToken"
      (json \ "_id").as[String] must_== token.id
      (json \ "_rev").as[String] must_== token._rev.get
      (json \ "accessType").as[String] must_== AccessType.Share.toString
      (json \ "resource" \ "id").as[String] must_== computeNode.id
      (json \ "resource" \ "type").as[String] must_==
        ResourceType.ComputeNode.toString
    }

    "get by ID" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db)
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
      val dao = new AccessTokenDAO(db)
      val computeNode = mockComputeNode
      val token = await(dao.create(AccessType.Share, computeNode))
      val tokens = await(dao.listFor(computeNode))
      tokens must haveSize(1)
      tokens must contain(token)
    }

    "delete" in new WithApplication(fakeApp) {
      import AccessToken._
      val dao = new AccessTokenDAO(db)
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
      def id: String = randomId
      def _rev: Option[String] = ???

      // Members declared in models.ComputeNode
      def backend: providers.hipache.Hipache.Backend = ???
      def containers: providers.machineshop.ContainerProvider = ???
      def managementUrl: String = ???
      def name: String = ???
      def serverId: String = ???

      // Members declared in models.OwnableModel
      def ownerIDs: Set[String] = ???

      // Members declared in models.UsableModel
      def userIDs: Set[String] = ???
    }
  }

}