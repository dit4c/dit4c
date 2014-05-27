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
    authProvider: AuthProvider,
    val db: CouchDB.Database)
    extends Controller with Utils {

  def login = Action { implicit request =>
    Redirect(authProvider.loginURL)
  }

  def logout = Action.async { implicit request =>
    render {
      case Accepts.Html() => Redirect(routes.Application.main("").url)
      case Accepts.Json() => NoContent
    }.withSession(session - "userId").withClearedJwt
  }

  def callback = Action.async { implicit request =>
    import CallbackResult.{Success, Failure, Invalid}
    import Future.successful
    authProvider.callbackHandler(request) match {
      case Success(identity) =>
        userDao.findWith(identity).flatMap {
          case Some(user) => successful(user)
          case None => userDao.createWith(identity)
        }.flatMap { user =>
          Redirect(routes.Application.main("login").url)
            .withSession(session + ("userId" -> user.id))
            .withUpdatedJwt
        }
      case Failure(msg) => successful(Forbidden(msg))
      case Invalid => successful(BadRequest)
    }
  }

}