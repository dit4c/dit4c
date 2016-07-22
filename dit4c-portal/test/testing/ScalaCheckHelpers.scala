package testing

import org.scalacheck.{Arbitrary, Gen}
import scalaoauth2.provider.AuthInfo
import com.mohiva.play.silhouette.api.LoginInfo
import services.IdentityService

trait ScalaCheckHelpers {
  implicit def arbAuthInfo[U](implicit arbU: Arbitrary[U]) = Arbitrary {
    for {
      user <- implicitly[Arbitrary[U]].arbitrary
      clientId <- implicitly[Arbitrary[Option[String]]].arbitrary
      scope <- implicitly[Arbitrary[Option[String]]].arbitrary
      redirectUri <- implicitly[Arbitrary[Option[String]]].arbitrary
    } yield AuthInfo(user, clientId, scope, redirectUri)
  }

  implicit val arbLoginInfo = Arbitrary {
    for {
      providerID <- Gen.identifier
      providerKey <- implicitly[Arbitrary[String]].arbitrary
    } yield LoginInfo(providerID, providerKey)
  }

  implicit val arbIdentityServiceUser = Arbitrary {
    for {
      userId <- Gen.identifier
      loginInfo <- arbLoginInfo.arbitrary
    } yield IdentityService.User(userId, loginInfo)
  }
}

object ScalaCheckHelpers extends ScalaCheckHelpers