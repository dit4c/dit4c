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
import akka.actor.ActorRef
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import providers.ContainerResolver
import providers.RoutingMapEmitter
import providers.machineshop.{MachineShop, ContainerProvider}
import scala.util.Try
import java.net.ConnectException
import models.AccessToken.AccessType

class ComputeNodeController @Inject() (
    val db: CouchDB.Database,
    mainController: Application,
    containerResolver: ContainerResolver) extends Controller with Utils {

  import RoutingMapEmitter.routingBackendFormat

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
    val backend = (json \ "backend").as[RoutingMapEmitter.Backend]

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
          .withBackend((json \ "backend").as[RoutingMapEmitter.Backend])
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
          nodeContainers <- containerDao.listFor(computeNode)
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

  // Images
  def createImage(nodeId: String) =
    Authenticated.async(parse.json) { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        import play.api.libs.ws._
        client(computeNode)("images")
          .signed { ws =>
            ws.withMethod("POST")
              .withHeaders("Content-Type" -> "application/json; charset=utf-8")
              .withBody(InMemoryBody(
                Json.prettyPrint(request.body).getBytes("utf-8")))
          }
          .map { response =>
            Status(response.status)(response.json)
          }
      })
    }

  def listImages(nodeId: String) = Authenticated.async { implicit request =>
    withComputeNode(nodeId) { computeNode =>
      client(computeNode)("images")
        .signed(_.withMethod("GET"))
        .map { response =>
          Ok(response.json)
        }
        .recover {
          // Best response when compute node is down?
          case e: ConnectException => Ok(JsArray())
        }
    }
  }

  def pullImage(nodeId: String, imageId: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        import play.api.libs.ws._
        client(computeNode)(s"images/$imageId/pull")
          .signed(_.withMethod("POST"))
          .map { response =>
            Status(response.status)("")
          }
      })
    }

  def removeImage(nodeId: String, imageId: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        import play.api.libs.ws._
        client(computeNode)(s"images/$imageId")
          .signed(_.withMethod("DELETE"))
          .map { response =>
            Status(response.status)("")
          }
      })
    }

  // Containers
  def listContainers(nodeId: String) = Authenticated.async { implicit request =>
    withComputeNode(nodeId)(asOwner { computeNode =>
      for {
        allContainers <- containerDao.list
        containers = allContainers.filter(_.computeNodeId == computeNode.id)
        dockerContainers <- containerProvider(computeNode).list
        dcMap = dockerContainers.map(c => (c.name -> c)).toMap
      } yield Ok(JsArray(containers.map { c =>
        val cnc = dcMap.get(containerResolver.asName(c))
        Json.obj(
          "id" -> c.id,
          "name" -> c.name,
          "computeNodeId" -> c.computeNodeId,
          "image" -> c.image,
          "ownerIDs" -> Set(c.ownerID),
          "active" -> cnc.map[JsBoolean](cnc => JsBoolean(cnc.active))
        )
      }))
    })
  }

  def stopContainer(nodeId: String, containerId: String) =
  Authenticated.async { implicit request =>
    withComputeNode(nodeId)(asOwner { computeNode =>
      containerDao.get(containerId).flatMap {
        case None =>
          // Doesn't exist
          Future.successful(NotFound)
        case Some(container) if container.computeNodeId != nodeId =>
          // Doesn't belong to this compute node
          Future.successful(NotFound)
        case Some(container) =>
          // Exists and belongs to this compute node
          for {
            dockerContainer <- containerProvider(computeNode)
                                  .get(cncName(container))
            _ <- dockerContainer
                  .map(_.stop)
                  .getOrElse(Future.successful(()))
          } yield dockerContainer match {
            case Some(dockerContainer) =>
              Ok(Json.obj(
                "id" -> container.id,
                "name" -> container.name,
                "computeNodeId" -> container.computeNodeId,
                "image" -> container.image,
                "ownerIDs" -> Set(container.ownerID),
                "active" -> JsBoolean(false)
              ))
            case None => NotFound
          }
      }
    })
  }

  def deleteContainer(nodeId: String, containerId: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        containerDao.get(containerId).flatMap {
          case None =>
            // Doesn't exist
            Future.successful(NotFound)
          case Some(container) if container.computeNodeId != nodeId =>
            // Doesn't belong to this compute node
            Future.successful(NotFound)
          case Some(container) =>
            // Exists and belongs to this compute node
            for {
              dockerContainer <- containerProvider(computeNode)
                                    .get(cncName(container))
              _ <- container.delete
              _ <- dockerContainer
                    .map(_.delete)
                    .getOrElse(Future.successful(()))
            } yield NoContent
        }
      })
    }

  // Owners & Users
  def listOwners(nodeId: String) = userListAction(nodeId, _.ownerIDs)

  def removeOwner(nodeId: String, userId: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner {
        case node if !node.ownerIDs.contains(userId) =>
          sync2async(NotFound("Specified owner doesn't exist."))
        case node if node.ownerIDs.size < 2 =>
          sync2async(Forbidden("Cannot remove last owner."))
        case node =>
          for {
            _ <- node.removeOwner(userId)
          } yield NoContent
      })
    }

  def listUsers(nodeId: String) = userListAction(nodeId, _.userIDs)

  def removeUser(nodeId: String, userId: String) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner {
        case node if !node.userIDs.contains(userId) =>
          throw new Exception(node.toString)
          sync2async(NotFound("Specified user doesn't exist."))
        case node =>
          for {
            _ <- node.removeUser(userId)
          } yield NoContent
      })
    }

  protected def containerProvider(computeNode: ComputeNode): ContainerProvider =
      new ContainerProvider(
        computeNode.managementUrl,
        () => keyDao.bestSigningKey.map(_.get.toJWK))

  protected def userListAction(
      nodeId: String,
      getSetOfIDs: ComputeNode => Set[String]) =
    Authenticated.async { implicit request =>
      withComputeNode(nodeId)(asOwner { computeNode =>
        for {
          owners <- Future.sequence(getSetOfIDs(computeNode).map(userDao.get))
        } yield Ok(JsArray(owners.flatten.toSeq.map(Json.toJson(_))))
      })
    }

  protected def client(computeNode: ComputeNode) = new MachineShop.Client(
      computeNode.managementUrl,
      () => keyDao.bestSigningKey.map(_.get.toJWK))

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

  private def cncName(container: models.Container) =
    containerResolver.asName(container)

  implicit lazy val tokenWriter = new Writes[AccessToken]() {
    override def writes(o: AccessToken) = Json.obj(
      "code" -> o.code,
      "type" -> o.accessType.toString.toLowerCase
    )
  }


}
