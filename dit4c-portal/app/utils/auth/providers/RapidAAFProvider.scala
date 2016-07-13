package utils.auth.providers

import com.mohiva.play.silhouette.impl.providers.SocialProvider
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile
import com.mohiva.play.silhouette.api.AuthInfo
import com.mohiva.play.silhouette.api.util.HTTPLayer
import scala.concurrent.Future
import com.mohiva.play.silhouette.api.util.ExtractableRequest
import play.api.mvc.Result
import play.api.libs.json.JsValue
import com.mohiva.play.silhouette.api.util.RequestPart
import pdi.jwt.JwtClaim
import pdi.jwt.JwtJson
import play.api.mvc.Results
import pdi.jwt.JwtAlgorithm
import play.api.libs.json.Json
import com.mohiva.play.silhouette.impl.providers.SocialProfileParser
import com.mohiva.play.silhouette.api.LoginInfo
import pdi.jwt.exceptions.JwtException
import com.mohiva.play.silhouette.api.Logger
import scala.util._

object RapidAAFProvider {

  case class AAFInfo(attributes: Map[String, String]) extends AuthInfo
  case class Settings(url: String, secret: String)

}


class RapidAAFProvider(
    val httpLayer: HTTPLayer,
    val settings: RapidAAFProvider.Settings) extends SocialProvider with Logger {
  val id = "rapidaaf"
  private val attributesClaim = "https://aaf.edu.au/attributes"

  type A = RapidAAFProvider.AAFInfo
  type Content = JsValue
  type Profile = CommonSocialProfile
  type Self = RapidAAFProvider
  type Settings = RapidAAFProvider.Settings

  override def authenticate[B]()(implicit request: ExtractableRequest[B]): Future[Either[Result, A]] =
    request.jwtClaim match {
      case None => Future.successful(Left(Results.TemporaryRedirect(settings.url)))
      case Some(jwt) => Future.successful {
        Right(RapidAAFProvider.AAFInfo((Json.parse(jwt.content) \ attributesClaim).as[Map[String, String]]))
      }
    }

  override def withSettings(f: Settings => Settings): Self =
    new RapidAAFProvider(httpLayer, f(settings))

  protected def buildProfile(authInfo: A): Future[Profile] = Future.successful {
    val loginInfo = LoginInfo(id, authInfo.attributes("edupersontargetedid"))
    // TODO: Extract profile data from attributes
    CommonSocialProfile(loginInfo, None, None, None, None, None)
  }

  protected def profileParser: SocialProfileParser[Content, Profile, A] =
    new SocialProfileParser[Content, Profile, A]() {
      override def parse(c: Content, authInfo: A) = buildProfile(authInfo)
    }

  protected def urls: Map[String,String] = Map.empty

  implicit class RapidAAFRequest[B](request: ExtractableRequest[B]) {
    def jwtClaim: Option[JwtClaim] =
      request.extractString("assertion", Some(Seq(RequestPart.FormUrlEncodedBody)))
        .map(token => JwtJson.decode(token, settings.secret, JwtAlgorithm.allHmac))
        .flatMap {
          case Success(v) => Some(v)
          case Failure(e) =>
            logger.error(e.getMessage)
            None
        }

  }

}