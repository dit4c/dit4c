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
  protected lazy val keyDao = new KeyDAO(db)
  protected lazy val userDao = new UserDAO(db)

  implicit class JwtHelper(response: Result)(implicit request: Request[_]) {
    def withUpdatedJwt(user: User): Future[Result] =
      for {
        containers <- userContainers(user)
        jwt <- createJWT(containers.map(_.name))
      } yield {
        response.withCookies(Cookie("dit4c-jwt", jwt, domain=getCookieDomain))
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

  private def createJWT(containers: Seq[String])(implicit request: Request[_]) =
    privateKeySet.map { jks =>
      val privateKey = jks.getKeys.head
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
      // Keyset URL, which we'll set because we have one
      header.setJWKURL(new java.net.URL(
          routes.AuthController.publicKeys().absoluteURL(request.secure)))
      val payload = new Payload(tokenString)
      val signer = new RSASSASigner(privateKey)
      val token = new JWSObject(header, payload)
      token.sign(signer)
      token.serialize
    }

  // Public keyset includes retired keys
  def publicKeySet: Future[JWKSet] =
    keys.map(ks => new JWKSet(ks.map(_.toJWK)).toPublicJWKSet)

  // Private keyset excludes retired keys
  private def privateKeySet: Future[JWKSet] =
    keys.map(ks => new JWKSet(ks.filter(!_.retired).map(_.toJWK)))

  lazy val keyNamespace: String =
    Play.current.configuration
      .getString("application.baseUrl")
      .getOrElse("DIT4C")

  // Get keys, and create a new key if necessary
  private def keys = keyDao.list.flatMap { keys =>
    if (keys.exists(!_.retired))
      Future.successful(keys)
    else
      keyDao.create(keyNamespace).map(keys :+ _)
  }

}