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
import providers.hipache.ContainerResolver
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.mvc.Http.RequestHeader
import models._

class AuthController @Inject() (
    authProviders: AuthProviders,
    containerResolver: ContainerResolver,
    val db: CouchDB.Database)
    extends Controller with Utils {

  def publicKeys = Action.async { implicit request =>
    for {
      keys <- keyDao.list
      publicKeySet = new JWKSet(keys.map(_.toJWK)).toPublicJWKSet
    } yield {
      Ok(Json.parse(publicKeySet.toJSONObject.toJSONString))
    }
  }

  def login(name: String) = Action.async { implicit request =>
    providerWithName(name) match {
      case Some(provider) =>
        provider.loginHandler(request)
      case None =>
        Future.successful {
          BadRequest("Login method doesn't exist.")
        }
    }
  }

  def logout = Action.async { implicit request =>
    render {
      case Accepts.Html() => Redirect(routes.Application.main("").url)
      case Accepts.Json() => NoContent
    }.withNewSession.withClearedJwt
  }

  def namedCallback(name: String) = callback(providerWithName(name))

  def unnamedCallback = callback(authProviders.providers)

  def renew = Authenticated.async { implicit request =>
    NoContent.withUpdatedJwt(request.user, containerResolver)
  }

  protected def callback(providers: Iterable[AuthProvider]) =
    Action.async { implicit request =>
      import CallbackResult._
      import Future.successful

      Future.sequence(providers.map(_.callbackHandler(request)))
        .map { result =>
          result
            .find(_ != Invalid) // Keep going until it's not invalid
            .getOrElse(Invalid) // or we exhaust all the options
        }
        .flatMap {
          case Success(identity) =>
            userDao.findWith(identity).flatMap {
              case Some(user) =>
                // If name & email are not populated, populate them
                user.update
                  .withName(user.name.orElse(identity.name))
                  .withEmail(user.email.orElse(identity.emailAddress))
                  .execIfDifferent(user)
              case None => userDao.createWith(identity)
            }.flatMap { user =>
              Redirect(routes.Application.main("login").url)
                .withSession(request.session + ("userId" -> user.id))
                .withUpdatedJwt(user, containerResolver)
            }
          case Failure(msg) => successful(Forbidden(msg))
          case Invalid => successful(BadRequest)
        }
    }

  protected def providerWithName(name: String): Option[AuthProvider] =
    authProviders.providers.find(_.name == name)

}