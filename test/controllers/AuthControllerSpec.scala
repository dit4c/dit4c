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

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class AuthControllerSpec extends PlaySpecification with SpecUtils {

  "AuthController" should {

    "provide JSON for public keys" in new WithApplication(fakeApp) {
      val controller = injector.getInstance(classOf[AuthController])

      val response = controller.publicKeys(FakeRequest())
      status(response) must_== 200
      val json = contentAsJson(response)
      (json \ "keys").asInstanceOf[JsArray].value.foreach { key =>
        (key \ "kty").as[String] must_== "RSA"
        (key \ "use").as[String] must_== "sig"
        (key \ "alg").as[String] must_== "RSA"
        (key \ "n").asOpt[String] must beSome[String]
        (key \ "e").asOpt[String] must beSome[String]
        (key \ "d").asOpt[String] must beNone
      }
    }
  }

}
