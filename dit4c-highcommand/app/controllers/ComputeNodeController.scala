package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import providers.db.CouchDB
import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import models._
import scala.concurrent.Future
import play.mvc.Http.RequestHeader
import providers.hipache.HipacheActor
import providers.hipache.Hipache
import akka.actor.ActorRef
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import providers.hipache.HipachePlugin
import providers.machineshop.MachineShop
import scala.util.Try
import java.net.ConnectException
import models.AccessToken.AccessType

class ComputeNodeController @Inject() (
    val db: CouchDB.Database,
    mainController: Application) extends Controller with Utils {

  import Hipache.hipacheBackendFormat

  def index: Action[AnyContent] = Action.async { implicit request =>
    render.async {
      case Accepts.Html() => mainController.main("compute-nodes")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create = Authenticated.async(parse.json) { implicit request =>
    val json = request.body
    val name = (json \ "name").as[String]
    val managementUrl = (json \ "managementUrl").as[String]
    val backend = (json \ "backend").as[Hipache.Backend]

    // Check this is a new server
    val fServerId: Future[Either[String, String]] =
      for {
        serverId <- MachineShop.fetchServerId(managementUrl)
            .map(Some(_))
            .fallbackTo(sync2async(None))
        existingNodes <- computeNodeDao.list
        attrs = (f: ComputeNode => String) => existingNodes.map(f)
      } yield {
        if (attrs(_.name).contains(name))
          Left("Node with same name already exists.")
        else if (attrs(_.managementUrl).contains(managementUrl))
          Left("Node with same URL already exists.")
        else
          serverId match {
            case None =>
              Left("Node was not contactable.")
            case Some(id) if (attrs(_.serverId).contains(id)) =>
              Left("Node with same server ID already exists.")
            case Some(id) =>
              Right(id)
          }
      }

    fServerId.flatMap {
      case Left(msg) => sync2async(BadRequest(msg))
      case Right(serverId) =>
        for {
          node <- computeNodeDao.create(request.user,
              name, serverId, managementUrl, backend)
        } yield {
          Created(Json.toJson(node))
        }
    }
  }

  def list: Action[AnyContent] = Authenticated.async { implicit request =>
    computeNodeDao.list map { nodes =>
      val json = JsArray(nodes.map(Json.toJson(_)))
      Ok(json)
    }
  }

  def update(id: String) = Authenticated.async(parse.json) { implicit request =>
    withComputeNode(id)(asOwner { computeNode =>
      val json = request.body

      for {
        updated <- computeNode.update
          .withName((json \ "name").as[String])
          .withManagementUrl((json \ "managementUrl").as[String])
          .withBackend((json \ "backend").as[Hipache.Backend])
          .exec()
      } yield Ok(Json.toJson(updated))
    })
  }

  def delete(id: String): Action[AnyContent] =
    Authenticated.async { implicit request =>
      withComputeNode(id)(asOwner { computeNode =>
        for {
          tokens <- accessTokenDao.listFor(computeNode)
          _ <- Future.sequence(tokens.map(_.delete))
          allContainers <- containerDao.list
          nodeContainers = allContainers.filter(_.computeNodeId == id)
          _ <- Future.sequence(nodeContainers.map(_.delete))
          _ <- computeNode.delete
        } yield NoContent
      })
    }

  // Access Tokens

  def createToken(nodeId: String) =
    Authenticated.async(parse.json) { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        val accessType = (request.body \ "type").as[String] match {
          case "share" => AccessType.Share
          case "own"   => AccessType.Own
        }
        for {
          token <- accessTokenDao.create(accessType, computeNode)
        } yield Created(Json.toJson(token))
      })
    }

  def listTokens(nodeId: String) = Authenticated.async { implicit request =>
    withComputeNode(nodeId)(asOwner { computeNode =>
      for {
        tokens <- accessTokenDao.listFor(computeNode)
      } yield Ok(JsArray(tokens.map(Json.toJson(_))))
    })
  }

  def redeemToken(nodeId: String, code: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId) { computeNode =>
        withToken(computeNode, code) { token =>
          import AccessToken.AccessType._
          for {
            token <- token.accessType match {
              case Own => computeNode.addOwner(request.user)
              case Share => computeNode.addUser(request.user)
            }
          } yield {
            SeeOther(routes.ContainerController.index.url)
          }
        }
      }
    }

  def deleteToken(nodeId: String, code: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        withToken(computeNode, code) { token =>
          for {
            _ <- token.delete
          } yield NoContent
        }
      })
    }


  protected def sync2async[A](obj: A) = Future.successful(obj)

  protected def withToken(
        computeNode: ComputeNode,
        code: String
      )(
        action: AccessToken => Future[Result]
      ): Future[Result] =
    for {
      tokens <- accessTokenDao.listFor(computeNode)
      possibleToken = tokens.find(_.code == code)
      result <- possibleToken match {
        case Some(token) => action(token)
        case None => sync2async(NotFound("Access code does not exist."))
      }
    } yield result

  protected def withComputeNode(
        nodeId: String
      )(
        action: ComputeNode => Future[Result]
      ): Future[Result] =
    for {
      possibleNode <- computeNodeDao.get(nodeId)
      result <- possibleNode match {
        case Some(node) => action(node)
        case None => sync2async(NotFound("Compute Node does not exist."))
      }
    } yield result

  protected def asOwner(
        action: ComputeNode => Future[Result]
      )(
        implicit request: AuthenticatedRequest[_]
      ): ComputeNode => Future[Result] = {
    case node if node.ownerIDs.contains(request.user.id) => action(node)
    case _ => sync2async(Forbidden("You do not own this compute node."))
  }

  implicit def nodeWriter(implicit request: AuthenticatedRequest[_]) =
    new Writes[ComputeNode] {
      override def writes(node: ComputeNode) = {
        val base =
          Json.obj(
            "id" -> node.id,
            "name" -> node.name,
            "owned" -> node.ownedBy(request.user),
            "usable" -> node.usableBy(request.user)
          )
        if (node.ownedBy(request.user))
          base ++ Json.obj(
            "managementUrl" -> node.managementUrl,
            "backend" -> node.backend
          )
        else
          base
      }
    }

  implicit lazy val tokenWriter = new Writes[AccessToken]() {
    override def writes(o: AccessToken) = Json.obj(
      "code" -> o.code,
      "type" -> o.accessType.toString.toLowerCase
    )
  }


}