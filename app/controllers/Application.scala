package controllers

import play.api._
import play.api.mvc._
import scala.io.Source
import com.nimbusds.jose._
import com.nimbusds.jose.jwk._
import com.nimbusds.jose.crypto.RSASSASigner
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.io.{BufferedWriter, FileWriter, File, FileNotFoundException}
import play.api.libs.json.Json
import scala.collection.JavaConversions._
import java.util.Calendar
import com.nimbusds.jwt.JWTParser
import scala.util.Try
import services.jwt._

class Application extends Controller {

  val callbackValidators: Seq[JWTValidator] = {
    val appConfig = Play.current.configuration
    appConfig.getObject("callback_signature_keys").map { config =>
      config.values.toSeq.map(_.unwrapped.toString).map { sigKey =>
        new services.jwt.JWSVerifier(sigKey)
      }
    }.getOrElse(Seq())
  }

  lazy val rapidAAFUrl =
    Play.current.configuration.getString("rapid-aaf-url").get

  val containers = (1 to 9).map(i => s"test$i").toList

  def index = Action { request =>
    Ok(views.html.index(containers, request.host))
  }

  def login = Action { implicit request =>
    val targetAfterLogin = request.headers.get("Referer").getOrElse("/")
    Ok(views.html.login(rapidAAFUrl))
      .withSession(session + ("redirect-on-callback" -> targetAfterLogin))
  }

  def callback = Action { implicit request =>
    request.body.asFormUrlEncoded.flatMap { form =>
      // Extract assertion
      form.get("assertion").flatMap(_.headOption)
    }.flatMap { potentialToken =>
      // Convert to JWT
      Try(JWTParser.parse(potentialToken)).toOption
    }.map { token =>
      // Check JWT validates
      callbackValidators.map(_(token)).flatten.headOption match {
        // Validated by one of them
        case Some(payload) =>
          val cookies = Cookie("dit4c-jwt",
              jwt(containers), domain=getCookieDomain)
          val url = request.session.get("redirect-on-callback").getOrElse("/")
          Redirect(url)
            .withCookies(cookies)
            .withSession(session - "redirect-on-callback")
        // Failed validation from all
        case None =>
          Forbidden
      }
    }.getOrElse(BadRequest)
  }

  def getCookieDomain(implicit request: RequestHeader): Option[String] =
    if (request.host.matches(".+\\..+")) Some("."+request.host)
    else None

  private def jwt(containers: List[String])(implicit request: RequestHeader) = {
    val privateKey = privateKeySet.getKeys.head
      .asInstanceOf[RSAKey].toRSAPrivateKey
    val tokenString = {
      val json = Json.obj(
          "iis" -> request.host,
          "iat" -> System.currentTimeMillis / 1000,
          "http://dit4c.github.io/authorized_containers" -> containers
        )
      json.toString
    }
    val header = new JWSHeader(JWSAlgorithm.RS256)
    val payload = new Payload(tokenString)
    val signer = new RSASSASigner(privateKey)
    val token = new JWSObject(header, payload)
    token.sign(signer)
    token.serialize
  }

  private def privateKeySet: JWKSet = {
    try {
      val content = Source.fromFile(privateKeysFile).mkString
      JWKSet.parse(content)
    } catch {
      case _: FileNotFoundException => createNewPrivateKeySet(privateKeysFile)
    }
  }


  private lazy val privateKeysFile = {
    val keyPath = Play.current.configuration
      .getString("keys.path").getOrElse("keys.json")
    new File(keyPath)
  }

  private def createNewPrivateKeySet(keyFile: File): JWKSet = {
    val keyLength = 4096 // Current recommended as of 2014
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(keyLength)
    val kp = generator.generateKeyPair
    val pub = kp.getPublic.asInstanceOf[RSAPublicKey]
    val priv = kp.getPrivate.asInstanceOf[RSAPrivateKey]
    val k = new RSAKey(pub, priv, Use.SIGNATURE, JWSAlgorithm.parse("RSA"),
        s"RSA key ($keyLength bit)", null, null, null)
    val keySet = new JWKSet(k);
    {
      val w = new BufferedWriter(new FileWriter(keyFile))
      // We need to explicitly state we want private keys too
      val serializedJson = keySet.toJSONObject(false).toString
      // Parse and unparse so we can pretty print for legibility
      w.write(Json.prettyPrint(Json.parse(serializedJson)))
      w.close
    }
    keySet
  }


}
