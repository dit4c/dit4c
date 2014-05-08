package providers.auth

import java.net.URL

import scala.util.Random

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import com.nimbusds.jose._
import com.nimbusds.jose.crypto.MACSigner

import play.api.test.FakeRequest

@RunWith(classOf[JUnitRunner])
class AAFAuthProviderSpec extends Specification {

  "AAF Auth Provider" should {

    val authProviderKey = Random.nextString(20)
    val authProvider = new RapidAAFAuthProvider(RapidAAFAuthProviderConfig(
      "RapidAAF", new URL("http://example.test/login"), authProviderKey
    ))

    "should produce an identity from a properly formatted request" in {
      val content =
        """|{
           |  "iss": "https://rapid.aaf.edu.au",
           |  "iat": 1397181481,
           |  "jti": "2bdmqlYf2Pmtyu_d4TT7MZ9xtcc44q9A",
           |  "nbf": 1397181421,
           |  "exp": 1397181601,
           |  "typ": "authnresponse",
           |  "aud": "https://example.test/",
           |  "https://aaf.edu.au/attributes": {
           |    "cn": "Tom Atkins",
           |    "mail": "t.atkins@fictional.edu.au",
           |    "displayname": "Mr Tom Atkins",
           |    "givenname": "Tom",
           |    "surname": "Atkins",
           |    "edupersonscopedaffiliation": "staff@fictional.edu.au",
           |    "edupersonprincipalname": "tatkin4@fictional.edu.au"
           |  }
           |}""".stripMargin
      val key: String = authProviderKey
      val serializedToken: String = {
        val jwsObject = new JWSObject(
            new JWSHeader(JWSAlgorithm.HS512), new Payload(content))
        jwsObject.sign(new MACSigner(key))
        jwsObject.serialize
      }
      val request = FakeRequest("POST", "/auth/callback")
        .withFormUrlEncodedBody("assertion" -> serializedToken)
      val callbackResult = authProvider.callbackHandler(request)
      callbackResult must beAnInstanceOf[CallbackResult.Success]
      val res = callbackResult.asInstanceOf[CallbackResult.Success]
      res.identity.uniqueId must_== "RapidAAF:tatkin4@fictional.edu.au"
    }

  }
}
