package providers.auth

import utils.jwt.JWSVerifier
import play.api.mvc.Request
import play.api.mvc.AnyContent
import scala.util.Try
import com.nimbusds.jwt.JWTParser
import play.api.libs.json._
import play.twirl.api.Html
import scala.concurrent.Future
import play.api.mvc.Results
import play.api.libs.ws.WSClient

class RapidAAFAuthProvider(config: RapidAAFAuthProvider.Config) extends AuthProvider {

  override def name = "rapidaaf"

  lazy val verifier = new JWSVerifier(config.key)

  override val callbackHandler = { request: Request[AnyContent] =>
    Future.successful {
      extractPayload(request).flatMap[CallbackResult] { payload =>
        val optAttrs = (payload \ "https://aaf.edu.au/attributes").asOpt[JsObject]
        optAttrs.flatMap { attrs =>
          Json.fromJson[Identity](attrs)(AttributeReader) match {
            case JsSuccess(identity, _) =>
              Some(CallbackResult.Success(identity))
            case _: JsError =>
              None
          }
        }
      }.getOrElse(CallbackResult.Invalid)
    }
  }

  override val loginHandler = { request: Request[AnyContent] =>
    Future.successful(Results.Redirect(config.url.toString))
  }

  private def extractPayload(request: Request[AnyContent]): Option[JsValue] =
    request.body.asFormUrlEncoded.flatMap { form =>
      // Extract assertion
      form.get("assertion").flatMap(_.headOption)
    }.flatMap { potentialToken =>
      // Convert to JWT
      Try(JWTParser.parse(potentialToken)).toOption
    }.flatMap(verifier(_)) // Check token validates
     .flatMap(v => Try(Json.parse(v)).toOption) // Convert to JSON


  implicit object AttributeReader extends Reads[Identity] {

    def reads(json: JsValue): JsResult[Identity] = json match {
      case obj: JsObject =>
        val attrs = obj.fieldSet.map(p => (p._1, p._2.as[String])).toMap
        val providerUserId = attrs.get("edupersontargetedid").get
        JsSuccess(new Identity {
          val uniqueId = s"RapidAAF:${providerUserId}"
          val emailAddress = attrs.get("mail")
          val name = attrs.get("cn")
        })
      case _ => JsError(Nil)
    }

  }
}


object RapidAAFAuthProvider extends AuthProviderFactory {
  case class Config(url: java.net.URL, key: String)

  def apply(
      config: play.api.Configuration,
      ws: WSClient): Iterable[AuthProvider] =
    for {
      c <- config.getConfig("rapidaaf")
      urlStr <- c.getString("url")
      url = new java.net.URL(urlStr)
      key <- c.getString("key")
    } yield new RapidAAFAuthProvider(
        RapidAAFAuthProvider.Config(url, key))

}
