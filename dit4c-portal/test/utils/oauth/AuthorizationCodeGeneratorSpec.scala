package utils.oauth

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import scalaoauth2.provider.AuthInfo
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.scalacheck.{Arbitrary, Gen}
import services.InstanceOAuthDataHandler.authInfoFormat
import testing.ScalaCheckHelpers

class AuthorizationCodeGeneratorSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck with ScalaCheckHelpers {

  "AuthorizationCodeGenerator" >> {

    "create codes with extractable details" >> prop({ (secret: String, issuer: String, authInfo: AuthInfo[TestUser]) =>
      {
        // Preconditions for this test
        secret.nonEmpty && issuer.nonEmpty && !issuer.exists(_.isControl)
      } ==> {
        val gen = new AuthorizationCodeGenerator(secret, issuer)
        val code = gen.create(authInfo)
        gen.decode[AuthInfo[TestUser]](code) must beSuccessfulTry(be_==(authInfo))
      }
    })

    "rejects codes which are too old" >> prop({ (secret: String, issuer: String, authInfo: AuthInfo[TestUser]) =>
      {
        // Preconditions for this test
        secret.nonEmpty && issuer.nonEmpty && !issuer.exists(_.isControl)
      } ==> {
        val gen = new AuthorizationCodeGenerator(secret, issuer)
        val code = gen.create(authInfo, Duration.Zero) // Should be expired
        gen.decode[AuthInfo[TestUser]](code) must beFailedTry
      }
    })
  }

  private case class TestUser(numField: Long, strField: String, boolField: Boolean, optField: Option[String])

  implicit private val testUserFormat: Format[TestUser] = (
      (__ \ 'nf).format[Long] and
      (__ \ 'sf).format[String] and
      (__ \ 'bf).format[Boolean] and
      (__ \ 'of).formatNullable[String]
    )(TestUser.apply _, unlift(TestUser.unapply))

  private val genTestUser =
    for {
      n <- implicitly[Arbitrary[Long]].arbitrary
      s <- implicitly[Arbitrary[String]].arbitrary
      b <- implicitly[Arbitrary[Boolean]].arbitrary
      o <- implicitly[Arbitrary[Option[String]]].arbitrary
    } yield TestUser(n, s, b, o)

  implicit private val arbTestUser: Arbitrary[TestUser] = Arbitrary(genTestUser)


}