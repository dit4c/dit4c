package models

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.concurrent.ExecutionContext
import play.api.test.PlaySpecification
import java.util.UUID
import providers.db.EphemeralCouchDBInstance
import providers.auth.Identity
import play.api.libs.ws.WS
import providers.db.CouchDB
import play.api.test.WithApplication
import utils.SpecUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.Interval

@RunWith(classOf[JUnitRunner])
class KeyDAOSpec extends PlaySpecification with SpecUtils {

  def millisSinceCreation(dt: DateTime): Long =
    (new Interval(dt, DateTime.now)).toDurationMillis

  "KeyDAO" should {

    "create a new key" in new WithApplication(fakeApp) {
      val dao = new KeyDAO(db)
      val namespace = "localhost.localdomain"
      val key = await(dao.create(namespace, 512))
      key.namespace must_== namespace
      millisSinceCreation(key.createdAt) must beBetween(0, 2000L)
      key.publicId must_== s"$namespace ${key.createdAt} [${key.id}]"
      // Check database has data
      val couchResponse = await(WS.url(s"${db.baseURL}/${key.id}").get)
      couchResponse.status must_== 200
      val json = couchResponse.json
      (json \ "type").as[String] must_== "Key"
      (json \ "_id").as[String] must_== key.id
      (json \ "_rev").as[Option[String]] must_== key._rev
      (json \ "namespace").as[String] must_== key.namespace
      (json \ "createdAt").as[String] must_== key.createdAt.toString()
      (json \ "retired").as[Boolean] must beFalse
      val keyJson = couchResponse.json \ "keyPair"
      (keyJson \ "kty").as[String] must_== "RSA"
      (keyJson \ "n").as[String] must_== key.toJWK.getModulus.toString
      (keyJson \ "e").as[String] must_== key.toJWK.getPublicExponent.toString
      (keyJson \ "d").as[String] must_== key.toJWK.getPrivateExponent.toString
    }

    "list all keys" in new WithApplication(fakeApp) {
      val dao = new KeyDAO(db)
      await(dao.list) must beEmpty
      val key = await(dao.create("localhost.localdomain", 512))
      await(dao.list) must haveSize(1)
    }

    "provide the best signing key" in new WithApplication(fakeApp) {
      val dao = new KeyDAO(db)
      // Create three keys sequentially, then retire the oldest
      val k1 = await(dao.create("localhost.localdomain", 512).flatMap(_.retire))
      val k2 = await(dao.create("localhost.localdomain", 512))
      val k3 = await(dao.create("localhost.localdomain", 512))
      await(dao.list) must haveSize(3)
      // Best key should be key2
      await(dao.bestSigningKey) must beSome(k2)
    }

  }

  "Key" should {

    "retire" in new WithApplication(fakeApp) {
      val dao = new KeyDAO(db)
      val key = await(dao.create("localhost.localdomain", 512))
      key.retired must beFalse
      val retiredKey = await(key.retire)
      retiredKey.retired must beTrue
      key.id must be(retiredKey.id)
      key._rev must not be(retiredKey._rev)
      await(dao.list) must haveSize(1)
    }

    "delete" in new WithApplication(fakeApp) {
      val dao = new KeyDAO(db)
      await(dao.list) must beEmpty
      val key = await(dao.create("localhost.localdomain", 512))
      await(dao.list) must haveSize(1)
      key.delete
      await(dao.list) must beEmpty
    }

  }

}