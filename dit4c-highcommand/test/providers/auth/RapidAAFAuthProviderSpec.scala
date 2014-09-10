package providers.auth

import java.net.URL
import scala.util.Random
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import com.nimbusds.jose._
import com.nimbusds.jose.crypto.MACSigner
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import org.specs2.ScalaCheck
import org.scalacheck.{Arbitrary, Gen, Prop}

@RunWith(classOf[JUnitRunner])
class RapidAAFAuthProviderSpec extends PlaySpecification with ScalaCheck {

  "AAF Auth Provider" should {

    val signingKeys = for {
      s <- Arbitrary.arbString.arbitrary.suchThat(_.length > 10)
    } yield s

    val targetedIDs = {
      def urlEncode(s: String) = java.net.URLEncoder.encode(s, "utf-8")
      val prefix = "https://rapid.aaf.edu.au!http://example.test!"
      for {
        s <- Gen.frequency(
            1 -> Arbitrary.arbString.arbitrary.map(urlEncode),
            1 -> Gen.identifier
          ).map(prefix + _)
           .suchThat(_.length <= 256)
      } yield s
    }

    "produce an identity from a properly formatted request" !
      Prop.forAll(targetedIDs, signingKeys) { (id: String, key: String) =>
        val authProvider = new RapidAAFAuthProvider(RapidAAFAuthProvider.Config(
          new URL("http://example.test/login"), key
        ))
        val content =
          s"""|{
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
              |    "edupersontargetedid": "$id",
              |    "edupersonscopedaffiliation": "staff@fictional.edu.au"
              |  }
              |}""".stripMargin
        val serializedToken: String = {
          val jwsObject = new JWSObject(
              new JWSHeader(JWSAlgorithm.HS512), new Payload(content))
          jwsObject.sign(new MACSigner(key))
          jwsObject.serialize
        }
        val request = FakeRequest("POST", "/auth/callback")
          .withFormUrlEncodedBody("assertion" -> serializedToken)

        val callbackResult = await(authProvider.callbackHandler(request))
        callbackResult must beAnInstanceOf[CallbackResult.Success]
        val res = callbackResult.asInstanceOf[CallbackResult.Success]
        res.identity.uniqueId must_== s"RapidAAF:$id"
      }

  }
}
