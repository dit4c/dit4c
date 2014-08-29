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

class ContainerController @Inject() (
    val db: CouchDB.Database,
    cnpHelper: ComputeNodeContainerHelper,
    mainController: Application) extends Controller with Utils {

  implicit class CNCHelper(cnp: MachineShop.Container) {
    def makeActive(shouldBeActive: Boolean): Future[MachineShop.Container] =
      if (cnp.active != shouldBeActive)
        if (shouldBeActive)
          cnp.start
        else
          cnp.stop
      else
        Future.successful(cnp)
  }

  def index = Action.async { implicit request =>
    render.async {
      case Accepts.Html() => mainController.main("containers")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val name = (json \ "name").as[String]
      val image = (json \ "image").as[String]
      val computeNodeId = (json \ "computeNodeId").as[String]
      val shouldBeActive = (json \ "active").as[Boolean]
      val fNode: Future[Option[ComputeNode]] =
        for {
          nodes <- computeNodeDao.get(computeNodeId)
          node = nodes.find(_.usableBy(request.user))
        } yield node

      fNode.flatMap {
        case None => Future.successful(BadRequest("Invalid compute node."))
        case Some(node) =>
          for {
            container <- containerDao.create(request.user, name, image, node)
            p <- cnpHelper.creator(container)
            _ <- HipacheInterface.put(p)
            cnContainer <- if (shouldBeActive) p.start else Future.successful(p)
            result = Created(Json.obj(
              "id" -> container.id,
              "name" -> container.name,
              "computeNodeId" -> container.computeNodeId,
              "image" -> container.image,
              "active" -> cnContainer.active
            ))
            resultWithJwt <- result.withUpdatedJwt(request.user)
          } yield resultWithJwt
      }
    }.getOrElse(Future.successful(BadRequest))
  }

  def list = Authenticated.async { implicit request =>
    containerPairs.map { pairs =>
      val user = request.user
      val json = JsArray(pairs.map { case (c, cnc) =>
          Json.obj(
            "id" -> c.id,
            "name" -> c.name,
            "computeNodeId" -> c.computeNodeId,
            "image" -> c.image,
            "active" -> cnc.map[JsBoolean](cnc => JsBoolean(cnc.active))
          )
        })
      Ok(json)
    }
  }

  def update(id: String) = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val shouldBeActive: Boolean = (json \ "active").as[Boolean]
      containerDao.get(id)
        .flatMap[Result] {
          case None =>
            Future.successful(NotFound)
          case Some(container) =>
            cnpHelper.resolver(container).flatMap {
              case None =>
                // TODO: Improve this handling
                Future.successful(NotFound)
              case Some(cnp) =>
                cnp.makeActive(shouldBeActive).map { updatedCnp =>
                  Ok(Json.obj(
                    "id" -> container.id,
                    "name" -> container.name,
                    "active" -> updatedCnp.active
                  ))
                }
            }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def delete(id: String) = Authenticated.async { implicit request =>
    containerDao.get(id)
      .flatMap[Result] {
        case None =>
          Future.successful(NotFound)
        case Some(container) =>
          cnpHelper.resolver(container).flatMap {
            case None =>
              // TODO: Improve this handling
              Future.successful(NotFound)
            case Some(cnp) =>
              for {
                _ <- container.delete
                _ <- cnp.delete
                _ <- HipacheInterface.delete(cnp)
              } yield NoContent
          }
      }
  }

  def checkNewName(name: String) = Authenticated.async { implicit request =>
    containerDao.list.map { containers =>
      if (containers.exists(p => p.name == name)) {
        Ok(Json.obj(
          "valid" -> false,
          "reason" -> "A container with that name already exists."
        ))
      } else {
        validateContainerName(name) match {
          case Right(_: Unit) =>
            Ok(Json.obj(
              "valid" -> true
            ))
          case Left(reason) =>
            Ok(Json.obj(
              "valid" -> false,
              "reason" -> reason
            ))
        }
      }
    }
  }

  def validateContainerName(name: String): Either[String, Unit] = {
    val c = ValidityCheck
    Seq(
      // Test and failure message
      c(_.length > 0,   "Name must be specified."),
      c(_.length <= 63, "Name must not be longer than 63 characters."),
      c(_.matches("""[a-z0-9\-]+"""),
          "Only lowercase letters, numbers and dashes are allowed."),
      c(!_.startsWith("-"),   "Names must not start with a dash."),
      c(!_.endsWith("-"),     "Names must not end with a dash."),
      c(!_.matches("[0-9]+"), "Names cannot only contain numbers.")
    ).find(!_.expr(name)).map(_.msg).toLeft(Right(Unit))
  }

  private case class ValidityCheck(
      val expr: String => Boolean,
      val msg: String)

  private def containerPairs(implicit request: AuthenticatedRequest[_]):
      Future[Seq[(Container, Option[MachineShop.Container])]] = {
    containerDao.list.flatMap { containers =>
      val userContainers = containers.filter(_.ownerIDs.contains(request.user.id))
      val r = cnpHelper.resolver // Use a single resolver instance
      Future.sequence(
        // For each container do a lookup with the resolver
        userContainers.map(r)
      ).map { results =>
        // Zip together container with optional MachineShop.Container
        userContainers.zip(results)
      }
    }
  }


  object HipacheInterface {
    import akka.pattern.ask
    import Hipache._
    import HipacheActor._

    implicit val timeout = Timeout(10, TimeUnit.SECONDS)

    def put(container: MachineShop.Container)(implicit req: Request[_]) =
      withHipache { hipache =>
        // TODO: Use ComputeNode backend to perform
        //hipache ? Put(container, container.proxyBackend)
        Future.successful(())
      }


    def delete(container: MachineShop.Container)(implicit req: Request[_]) =
      withHipache { hipache =>
        hipache ? Delete(container)
      }

    private def withHipache[A](f: ActorRef => Future[A]): Future[Unit] =
      hipacheActor.map {
        case Some(actorRef) => f(actorRef).map(_ => ())
        case None => Future.successful(())
      }

    private def hipacheActor: Future[Option[ActorRef]] =
      Play.current.plugin[HipachePlugin] match {
        case Some(plugin) => plugin.client
        case None => Future.successful(None)
      }

    private implicit def asFrontend(
        c: MachineShop.Container)(implicit req: Request[_]): Frontend = {
      Frontend(c.name, s"${c.name}.${req.host}")
    }

  }

}