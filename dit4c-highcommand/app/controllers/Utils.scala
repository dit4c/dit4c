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
import play.api.libs.json.{Json,Writes}
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
import providers.hipache._
import akka.util.Timeout
import java.util.concurrent.TimeUnit

trait Utils extends Results {
  import scala.language.implicitConversions

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  protected def db: CouchDB.Database

  protected lazy val accessTokenDao = new AccessTokenDAO(db)
  protected lazy val containerDao = new ContainerDAO(db)
  protected lazy val keyDao = new KeyDAO(db)
  protected lazy val userDao = new UserDAO(db)
  protected lazy val computeNodeDao = new ComputeNodeDAO(db, keyDao)

  implicit class JwtHelper(response: Result)(implicit request: Request[_]) {
    def withUpdatedJwt(user: User, cr: ContainerResolver): Future[Result] =
      for {
        containers <- userContainers(user)
        jwt <- createJWT(containers.map(cr.asName))
      } yield response.withCookies(jwtCookie(jwt))

    def withClearedJwt: Future[Result] =
      Future.successful(response.withCookies(jwtCookie("")))

    protected def jwtCookie(data: String): Cookie =
      Cookie("dit4c-jwt", data, domain=getCookieDomain)
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
    bestPrivateKey.map { jwk =>
      val privateKey = jwk.toRSAPrivateKey
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

  // While it's possible not to have a valid key, it's a pretty big error
  private def bestPrivateKey: Future[RSAKey] =
    keyDao.bestSigningKey.map {
      case Some(k) => k.toJWK
      case None =>
        throw new RuntimeException("No valid private keys are available!")
    }


  case class HipacheInterface(containerResolver: ContainerResolver) {
    import Hipache._

    implicit val timeout = Timeout(10, TimeUnit.SECONDS)

    def remapAll(node: ComputeNode) =
      withHipache { hipache =>
        for {
          containers <- containerDao.listFor(node)
          _ <- Future.sequence(containers.map(hipache.put(_, node)))
        } yield ()
      }

    def put(container: Container, node: ComputeNode) =
      withHipache { hipache =>
        hipache.put(container, node)
      }

    def delete(container: Container) =
      withHipache { hipache =>
        hipache.delete(container)
      }

    private def withHipache[A](f: HipacheClient => Future[A]): Future[Unit] =
      hipacheClient.map {
        case Some(client) => f(client).map(_ => ())
        case None => Future.successful(())
      }

    private def hipacheClient: Future[Option[HipacheClient]] =
      Play.current.plugin[HipachePlugin] match {
        case Some(plugin) => plugin.client
        case None => Future.successful(None)
      }

    private implicit def asFrontend(c: Container): Frontend =
      containerResolver.asFrontend(c)

    private implicit def asBackend(node: ComputeNode): Backend =
      node.backend

  }

  implicit lazy val userWriter = new Writes[User]() {
    override def writes(u: User) = Json.obj(
      "id"    -> u.id,
      "name"  -> u.name,
      "email" -> u.email,
      "identities" -> u.identities.map { s =>
        val Array(t, id) = s.split(":", 2)
        Map("type" -> t, "id" -> id)
      }
    )
  }

}
