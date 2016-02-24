package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee.Enumerator
import providers.db.CouchDB
import com.google.inject.Inject
import com.osinka.slugify.Slugify
import scala.concurrent.ExecutionContext
import models._
import scala.concurrent.Future
import scala.concurrent.duration._
import play.mvc.Http.RequestHeader
import providers.machineshop.MachineShop
import providers.hipache.ContainerResolver
import spray.http.StatusCodes.ServerError
import play.api.libs.iteratee.Enumeratee
import akka.util.ByteString

class ContainerController @Inject() (
    val db: CouchDB.Database,
    mainController: Application,
    containerResolver: ContainerResolver) extends Controller with Utils {

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

  def create = Authenticated.async(parse.json) { implicit request =>
    val json = request.body
    val name = (json \ "name").as[String]
    val image = (json \ "image").as[String]
    val computeNodeId = (json \ "computeNodeId").as[String]
    val shouldBeActive = (json \ "active").as[Boolean]

    if (name.isEmpty) {
      Future.successful(BadRequest("Name cannot be blank."))
    } else {
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
            p <- createComputeNodeContainer(container)
            cnContainer <- if (shouldBeActive) p.start else Future.successful(p)
            result = Created(Json.obj(
              "id" -> container.id,
              "name" -> container.name,
              "computeNodeId" -> container.computeNodeId,
              "image" -> container.image,
              "active" -> cnContainer.active
            ))
            resultWithJwt <- result.withUpdatedJwt(
                request.user, containerResolver)
          } yield resultWithJwt
      }.recover {
        case e: java.net.ConnectException =>
          Logger.warn(e.getMessage)
          InternalServerError("Unable to contact compute node.")
        case e: Throwable =>
          Logger.warn(e.getMessage)
          InternalServerError(e.getMessage)
      }
    }
  }

  def get(id: String) = Authenticated.async { implicit request =>
    withContainer(id) { container =>
      for {
        cnc <- fallbackToMissing(resolveComputeNodeContainer)(container)
      } yield {
        Ok(toJson(container, cnc))
      }
    }.recover {
      case e: Throwable =>
        Logger.warn(e.getMessage)
        InternalServerError(e.getMessage)
    }
  }

  def export(id: String) = Authenticated.async { implicit request =>
    withContainer(id) { container =>
      for {
        clientInstance <- client(container)
        (headers, body) <-
          clientInstance(s"containers/${cncName(container)}/export", 1.day)
            .signedAsStream(_.withMethod("GET"))
      } yield {
        val stream = utils.Buffering.diskBuffer(body)
        Status(headers.status)
          .chunked(stream)
          .as("application/x-tar")
          .withHeaders(
              "X-Accel-Buffering" -> "off", // Nginx should just let it stream
              "Content-Disposition" ->
                s"attachment; filename=${Slugify(container.name)}.tar")
      }
    }
  }

  def list = Authenticated.async { implicit request =>
    containerPairs.map { pairs =>
      val user = request.user
      val json = JsArray(pairs.map((toJson _).tupled))
      Ok(json)
    }
  }

  def redirect(id: String) = Authenticated.async { implicit request =>
    withContainer(id) { container =>
      val url = containerResolver.asUrl(container).toString()
      TemporaryRedirect(url).withUpdatedJwt(request.user, containerResolver)
    }
  }

  def update(id: String) = Authenticated.async(parse.json) { implicit request =>
    val json = request.body
    val name = (json \ "name").as[String]
    val shouldBeActive = (json \ "active").as[Boolean]
    withContainer(id) { container =>
      for {
        updatedContainer <- container.update
          .withName(name)
          .execIfDifferent[Container](container)
        maybeCnp <- resolveComputeNodeContainer(container)
        maybeUpdatedCnp <- maybeCnp match {
          case None =>
            Future.successful(None)
          case Some(cnp) =>
            cnp.makeActive(shouldBeActive).map(Some(_))
        }
      } yield {
        Ok(Json.obj(
          "id" -> updatedContainer.id,
          "name" -> updatedContainer.name,
          "active" ->
            maybeUpdatedCnp.map[JsBoolean](cnc => JsBoolean(cnc.active))
        ))
      }
    }.recover {
      case e: Throwable =>
        Logger.warn(e.getMessage)
        InternalServerError(e.getMessage)
    }
  }

  def delete(id: String) = Authenticated.async { implicit request =>
    containerDao.get(id)
      .flatMap[Result] {
        case None =>
          Future.successful(NotFound)
        case Some(container) if !container.ownerIDs.contains(request.user.id) =>
          Future.successful(Forbidden)
        case Some(container) =>
          // If resolution is successful, but doesn't return a container, then
          // we know the container doesn't exist where it should be. It's safe
          // to delete our record for it under those circumstances, so we want
          // to silently ignore it.
          def deleteOrIgnore(possibleContainer: Option[MachineShop.Container]) =
            possibleContainer.map(_.delete).getOrElse {
              Logger.warn(
                s"${container.name} missing on compute node. Deleting anyway."
              )
              Future.successful(())
            }
          // Delete the container record and the actual container.
          resolveComputeNodeContainer(container).flatMap { possibleContainer =>
            for {
              _ <- container.delete
              _ <- deleteOrIgnore(possibleContainer)
            } yield NoContent
          }.recover {
            case e: java.net.ConnectException =>
              Logger.warn(s"${e.getMessage} ⇒ aborting delete")
              InternalServerError("Unable to contact compute node.")
          }
      }
  }

  protected def createComputeNodeContainer(container: Container) =
      for {
        node <- computeNodeDao.get(container.computeNodeId)
        c <- node.get.containers.create(cncName(container), container.image)
      } yield c

  protected def resolveComputeNodeContainer(container: Container) =
      for {
        node <- computeNodeDao.get(container.computeNodeId)
        c <- node.get.containers.get(cncName(container))
      } yield c

  private case class ValidityCheck(
      val expr: String => Boolean,
      val msg: String)

  private def containerPairs(implicit request: AuthenticatedRequest[_]):
      Future[Seq[(Container, Option[MachineShop.Container])]] = {
    containerDao.list.flatMap { containers =>
      val userContainers = containers.filter(_.ownerIDs.contains(request.user.id))
      // Use a single resolver instance, and catch errors in resolution
      val r = fallbackToMissing(resolveComputeNodeContainer)
      Future.sequence(
        // For each container do a lookup with the resolver
        userContainers.map(r)
      ).map { results =>
        // Zip together container with optional MachineShop.Container
        userContainers.zip(results)
      }
    }
  }

  protected def withContainer(
        containerId: String
      )(
        action: Container => Future[Result]
      ): Future[Result] =
    for {
      possibleContainer <- containerDao.get(containerId)
      result <- possibleContainer match {
        case Some(node) => action(node)
        case None => Future.successful(NotFound("Container does not exist."))
      }
    } yield result

  protected def client(container: Container) =
    for {
      computeNode <- computeNodeDao.get(container.computeNodeId).map(_.get)
    } yield {
      new MachineShop.Client(
        computeNode.managementUrl,
        () => keyDao.bestSigningKey.map(_.get.toJWK))
    }

  private def hipache = HipacheInterface(containerResolver)

  private def cncName(container: models.Container) =
    containerResolver.asFrontend(container).name

  private def toJson(c: Container, cnc: Option[MachineShop.Container]) =
      Json.obj(
        "id" -> c.id,
        "name" -> c.name,
        "computeNodeId" -> c.computeNodeId,
        "image" -> c.image,
        "active" -> cnc.map[JsBoolean](cnc => JsBoolean(cnc.active))
      )

  // Given a function which returns a future optional object, catch any
  // errors with the future and resolve with "None" instead.
  private def fallbackToMissing[A, B](f: A => Future[Option[B]]) =
    { ft: Future[Option[B]] =>
      ft.recover {
        case e =>
          Logger.warn(s"${e.getMessage} ⇒ resolving to None")
          None
      }
    } compose f


}
