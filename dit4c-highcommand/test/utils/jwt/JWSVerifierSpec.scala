package utils.jwt

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._

import com.nimbusds.jwt._
import com.nimbusds.jose._

import scala.util.Random

import com.nimbusds.jose.crypto._

import org.specs2.specification.Fixture
import org.specs2.execute.AsResult

@RunWith(classOf[JUnitRunner])
class JWSVerifierSpec extends Specification {

  "JWS Verifier" should {

    val hmacAlgorithms = new Fixture[JWSAlgorithm]{
      def apply[R : AsResult](f: JWSAlgorithm => R) = {
        import JWSAlgorithm._
        import org.specs2.execute._
        // test with HMAC algorithms
        Seq(HS256, HS384, HS512).foldLeft(Success(): Result) { (res, i) =>
          res and AsResult(f(i))
        }
      }
    }

    "should return Some[String] on successful validation" ! hmacAlgorithms { hmacAlg =>
      val content = "Hello, world!"
      val key: String = Random.nextString(20)
      val serializedToken: String = {
        val jwsObject = new JWSObject(
            new JWSHeader(hmacAlg), new Payload(content))
        jwsObject.sign(new MACSigner(key))
        jwsObject.serialize
      }

      val jwsToken: JWT = JWTParser.parse(serializedToken)
      val validator = new JWSVerifier(key)

      validator(jwsToken) must beSome[String]
    }

    "should return None for invalid signatures" ! hmacAlgorithms { hmacAlg =>
      val content = "Hello, world!"
      val key: String = Random.nextString(20)
      val serializedToken: String = {
        val jwsObject = new JWSObject(
            new JWSHeader(hmacAlg), new Payload(content))
        // Sign with altered key
        jwsObject.sign(new MACSigner(key + "!"))
        jwsObject.serialize
      }
      val jwsToken: JWT = JWTParser.parse(serializedToken)
      val validator = new JWSVerifier(key)
      validator(jwsToken) must beNone
    }

    "should return None if not signed" in {
      val content = "Hello, world!"
      val key: String = Random.nextString(20)
      val serializedToken: String = {
        val jwt = new PlainObject(new Payload(content))
        jwt.serialize
      }
      val jwsToken: JWT = JWTParser.parse(serializedToken)
      val validator = new JWSVerifier(key)
      validator(jwsToken) must beNone
    }

  }
}
