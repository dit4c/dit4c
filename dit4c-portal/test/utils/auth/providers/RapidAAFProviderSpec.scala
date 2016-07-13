package utils.auth.providers

import org.specs2.mutable.Specification
import org.specs2.concurrent.ExecutionEnv
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import pdi.jwt.JwtAlgorithm
import scala.util.Random
import pdi.jwt.JwtJson
import play.api.test.FakeRequest
import play.api.libs.ws.ahc.AhcWSClient
import com.mohiva.play.silhouette.api.util.PlayHTTPLayer
import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import akka.stream.Materializer
import java.time.Instant
import com.mohiva.play.silhouette.impl.providers.SocialProfile
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile
import play.api.mvc.Result

class RapidAAFAuthProviderSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck {

  "AAF Auth Provider" should {

    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher
    implicit val mat: Materializer = ActorMaterializer()
    implicit val arbHmacAlg: Arbitrary[JwtAlgorithm] = Arbitrary(Gen.oneOf(JwtAlgorithm.allHmac))
    val httpLayer = new PlayHTTPLayer(AhcWSClient())

    val signingKeys = Arbitrary.arbString.arbitrary.suchThat(_.length >= 32)

    val targetedIDs = {
      def urlEncode(s: String) = java.net.URLEncoder.encode(s, "utf-8")
      val prefix = "https://rapid.aaf.edu.au!http://example.test!"
      for {
        n <- Gen.choose(1, 256-prefix.length)
        s <- Gen.frequency(
          1 -> Gen.listOfN(n, Arbitrary.arbChar.arbitrary).map(_.mkString).map(urlEncode),
          1 -> Gen.identifier
        )
      } yield prefix + s
    }

    "redirect to URL if JWT isn't present" >> {
      val url = "http://example.test/login"
      val authProvider = new RapidAAFProvider(
          httpLayer,
          RapidAAFProvider.Settings(url, "notakeyweareusing"))
      authProvider.authenticate()(FakeRequest("POST", "/authenticate/rapidaaf")) must beLeft[Result] {
        beLikeA[Result] {
          case response =>
            (response.header.status must be_==(307)) and
            (response.header.headers("location") must be_==(url))
        }
      }.await
    }

    "produce AuthInfo from a properly formatted request" >> prop({ (id: String, key: String, alg: JwtAlgorithm) =>
        val authProvider = new RapidAAFProvider(
            httpLayer,
            RapidAAFProvider.Settings("http://example.test/login", key))
        val content =
          s"""|{
              |  "iss": "https://rapid.aaf.edu.au",
              |  "iat": 1397181481,
              |  "jti": "2bdmqlYf2Pmtyu_d4TT7MZ9xtcc44q9A",
              |  "nbf": ${Instant.now.getEpochSecond - 1},
              |  "exp": ${Instant.now.getEpochSecond + 59},
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
        val serializedToken: String = JwtJson.encode(content, key, alg)
        val request = FakeRequest("POST", "/authenticate/rapidaaf")
          .withFormUrlEncodedBody("assertion" -> serializedToken)
        authProvider.authenticate()(request) must beRight[RapidAAFProvider.AAFInfo]({
          beLikeA[RapidAAFProvider.AAFInfo] {
            case RapidAAFProvider.AAFInfo(attributes) =>
              attributes must havePairs(
                "cn" -> "Tom Atkins",
                "mail" -> "t.atkins@fictional.edu.au",
                "displayname" -> "Mr Tom Atkins",
                "givenname" -> "Tom",
                "surname" -> "Atkins",
                "edupersontargetedid" -> id,
                "edupersonscopedaffiliation" -> "staff@fictional.edu.au")
            case _ => ko
          }
        }).await
      }).setGen1(targetedIDs)
        .setGen2(signingKeys)
        .noShrink

    "retrieve SocialProfile from AuthInfo" >> prop({ (id: String, key: String) =>
        val authProvider = new RapidAAFProvider(
            httpLayer,
            RapidAAFProvider.Settings("http://example.test/login", key))
        val authInfo = RapidAAFProvider.AAFInfo(Map(
          "cn" -> "Tom Atkins",
          "mail" -> "t.atkins@fictional.edu.au",
          "displayname" -> "Mr Tom Atkins",
          "givenname" -> "Tom",
          "surname" -> "Atkins",
          "edupersontargetedid" -> id,
          "edupersonscopedaffiliation" -> "staff@fictional.edu.au"))
        authProvider.retrieveProfile(authInfo) must {
          beLikeA[SocialProfile] {
            case CommonSocialProfile(loginInfo, _, _, _, _, _) =>
              (loginInfo.providerID must be_==(authProvider.id)) and
              (loginInfo.providerKey must be_==(id))
            case _ => ko
          }.await
        }
    }).setGen1(targetedIDs)
      .setGen2(signingKeys)
      .noShrink

  }
}