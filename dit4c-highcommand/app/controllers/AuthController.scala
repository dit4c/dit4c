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
import providers.ContainerResolver
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.mvc.Http.RequestHeader
import models._

class AuthController @Inject() (
    authProviders: AuthProviders,
    containerResolver: ContainerResolver,
    val db: CouchDB.Database)
    extends Controller with Utils {

  val log = play.api.Logger

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

  def merge(name: String) = Authenticated.async { implicit request =>
    providerWithName(name) match {
      case Some(provider) =>
        provider.loginHandler(request)
          .map(_.addingToSession("mergeTo" -> request.user.id))
      case None =>
        Future.successful(BadRequest("Login method doesn't exist."))
    }
  }
  
  def confirmMerge = Authenticated.async { implicit request =>
    (request.session.get("mergeUserId") match {
      case Some(id) => userDao.get(id)
      case None => Future.successful(None)
    }) flatMap {
      case Some(mergeUser) =>
        for {
          containers <- containerDao.list
          computeNodes <- computeNodeDao.list
          userContainers = containers.filter(_.ownerID == mergeUser.id)
          userComputeNodes = computeNodes.filter { cn =>
            (cn.ownerIDs ++ cn.userIDs).contains(mergeUser.id)
          }
          replaceID = (s: Set[String]) => s.map {
            case v if v == mergeUser.id => request.user.id
            case v => v
          }
          _ <- Future.sequence {
            userContainers.map { container =>
              container.update.withOwner(request.user.id)
            }.map(_.exec)
          }
          _ <- Future.sequence {
            userComputeNodes.map { cn =>
              cn.update
                .withOwners(replaceID(cn.ownerIDs))
                .withUsers(replaceID(cn.userIDs))
            }.map(_.exec)
          }
          updatedUser <- request.user.update
            .withIdentities(request.user.identities ++ mergeUser.identities)
            .exec()
          _ <- mergeUser.delete
        } yield Ok(Json.toJson(updatedUser))
      case None =>
        Future.successful(BadRequest("No merge user in session."))
    }
  }
  
  def cancelMerge = Authenticated { implicit request =>
    Redirect(routes.Application.main("account").url)
      .withSession(request.session - "mergeUserId")
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
            for {
              optSessionUser <- fetchUser(request)
              optIdentifiedUser <- userDao.findWith(identity)
              s = request.session
              // Only merge if merge request and users aren't the same
              optMergeUser = optSessionUser
                .filter(isMergeRequest)
                .filter(u => Some(u.id) != optIdentifiedUser.map(_.id))
              r <- (optMergeUser, optIdentifiedUser) match {
                // Merge of two existing users
                case (Some(userA), Some(userB)) =>
                  log.info(s"Merge request: ${userA.id}/${userB.id} $identity")
                  userDao.updateWithIdentity(userB, identity)
                    .flatMap(redirectToMergeConfirmation(userA))
                // Adding new identity to existing user
                case (Some(user), None) =>
                  log.info(s"Merging new identity (${user.id}): $identity")
                  userDao.updateWithIdentity(user, identity)
                    .flatMap(redirectAfterLogin)
                // Login of existing user
                case (None, Some(user)) =>
                  log.info(s"Login (${user.id}): $identity")
                  userDao.updateWithIdentity(user, identity)
                    .flatMap(redirectAfterLogin)
                // New user
                case (None, None) =>
                  log.info(s"New User: $identity")
                  userDao.createWith(identity)
                    .flatMap(redirectAfterLogin)
              }
            } yield r
          case Failure(msg) => successful(Forbidden(msg))
          case Invalid => successful(BadRequest)
        }
    }

  protected def isMergeRequest(user: User)(implicit request: Request[_]) =
    request.session.get("mergeTo") == Some(user.id)

  protected def redirectToMergeConfirmation(
      userA: User)(userB: User)(implicit request: Request[_]): Future[Result] =
    Redirect(routes.Application.main("account/merge").url)
      .withSession(request.session - "mergeTo" + ("mergeUserId" -> userB.id))
      .withUpdatedJwt(userA, containerResolver)

  protected def redirectAfterLogin(
      user: User)(implicit request: Request[_]): Future[Result] =
    Redirect(routes.Application.main("login").url)
      .withSession(request.session + ("userId" -> user.id) - "mergeTo")
      .withUpdatedJwt(user, containerResolver)

  protected def providerWithName(name: String): Option[AuthProvider] =
    authProviders.providers.find(_.name == name)

}
