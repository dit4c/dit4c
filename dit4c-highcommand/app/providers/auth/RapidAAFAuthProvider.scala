package providers.auth

import utils.jwt.JWSVerifier
import play.api.mvc.Request
import play.api.mvc.AnyContent
import scala.util.Try
import com.nimbusds.jwt.JWTParser
import play.api.libs.json._
import play.twirl.api.Html

class RapidAAFAuthProvider(config: RapidAAFAuthProviderConfig) extends AuthProvider {

  override def name = "rapidaaf"

  lazy val verifier = new JWSVerifier(config.key)

  override val callbackHandler = { request: Request[AnyContent] =>
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

  override val loginURL = config.url.toString

  override val loginButton = (url: String) => Html(
    s"""|<a target="_self" href="$url">
        |  <img class="img-responsive center-block" alt="Login with AAF"
        |       src="https://rapid.aaf.edu.au/aaf_service_866x193.png"/>
        |</a>
        |""".stripMargin
  )


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
          val uniqueId = s"${config.id}:${providerUserId}"
          val emailAddress = attrs.get("mail")
          val name = attrs.get("cn")
        })
      case _ =>
        JsError(Nil)
    }

  }
}

case class RapidAAFAuthProviderConfig(id: String, url: java.net.URL, key: String)

object RapidAAFAuthProvider extends AuthProviderFactory {

  def apply(config: play.api.Configuration) =
    for {
      c <- config.getConfig("rapidaaf")
      id = c.getString("id").getOrElse("RapidAAF")
      urlStr <- c.getString("url")
      url = new java.net.URL(urlStr)
      key <- c.getString("key")
    } yield new RapidAAFAuthProvider(
        new RapidAAFAuthProviderConfig(id, url, key))

}