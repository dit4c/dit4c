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
import utils.jwt._
import providers.auth._
import com.google.inject.Inject
import providers.db.CouchDB
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.mvc.Http.RequestHeader
import models._

trait Utils extends Results {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  protected def db: CouchDB.Database

  protected lazy val computeNodeDao = new ComputeNodeDAO(db)
  protected lazy val containerDao = new ContainerDAO(db)
  protected lazy val userDao = new UserDAO(db)

  implicit class JwtHelper(response: Result)(implicit request: Request[_]) {
    def withUpdatedJwt(user: User): Future[Result] =
      userContainers(user).map { containers =>
        response.withCookies(Cookie("dit4c-jwt",
            jwt(containers.map(_.name)), domain=getCookieDomain))
      }
    def withClearedJwt: Future[Result] =
      Future.successful {
        response.withCookies(
            Cookie("dit4c-jwt", "", domain=getCookieDomain))
      }
  }

  class AuthenticatedRequest[A](val user: User, request: Request[A])
    extends WrappedRequest[A](request)

  object Authenticated extends ActionBuilder[AuthenticatedRequest] {
    override def invokeBlock[A](
        request: Request[A],
        block: (AuthenticatedRequest[A]) => Future[Result]
        ): Future[Result] = {
      fetchUser(request).flatMap { possibleUser =>
        possibleUser match {
          case Some(user) => block(new AuthenticatedRequest(user, request))
          case None => Future.successful(Forbidden)
        }
      }
    }
  }

  protected def getCookieDomain(implicit request: Request[_]): Option[String] =
    if (request.host.matches(".+\\..+")) Some("."+request.host)
    else None

  protected def userContainers(user: User): Future[Seq[Container]] =
    containerDao.list.map { containers =>
      containers.filter(_.ownerIDs.contains(user.id))
    }

  protected def fetchUser(implicit request: Request[_]): Future[Option[User]] =
    request.session.get("userId")
      .map(userDao.get) // Get user if userId exists
      .getOrElse(Future.successful(None))

  private def jwt(containers: Seq[String])(implicit request: Request[_]) = {
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

  def publicKeySet: JWKSet = privateKeySet.toPublicJWKSet

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