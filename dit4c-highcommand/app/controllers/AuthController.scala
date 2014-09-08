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

class AuthController @Inject() (
    authProviders: AuthProviders,
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

  def login(name: String) = Action { implicit request =>
    authProviders.providers.find(_.name == name) match {
      case Some(provider) =>
        Redirect(provider.loginURL)
      case None =>
        BadRequest("Login method doesn't exist.")
    }
  }

  def logout = Action.async { implicit request =>
    render {
      case Accepts.Html() => Redirect(routes.Application.main("").url)
      case Accepts.Json() => NoContent
    }.withNewSession.withClearedJwt
  }

  def callback = Action.async { implicit request =>
    import CallbackResult._
    import Future.successful

    val result: CallbackResult =
      authProviders.providers
        .map(_.callbackHandler(request))
        .find(_ != Invalid) // Keep going until it's not invalid
        .getOrElse(Invalid) // or we exhaust all the options

    result match {
      case Success(identity) =>
        userDao.findWith(identity).flatMap {
          case Some(user) => successful(user)
          case None => userDao.createWith(identity)
        }.flatMap { user =>
          Redirect(routes.Application.main("login").url)
            .withSession(request.session + ("userId" -> user.id))
            .withUpdatedJwt(user)
        }
      case Failure(msg) => successful(Forbidden(msg))
      case Invalid => successful(BadRequest)
    }
  }

}