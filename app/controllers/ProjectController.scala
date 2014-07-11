package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import providers.db.CouchDB
import com.google.inject.Inject
import scala.concurrent.ExecutionContext
import models._
import scala.concurrent.Future

class ProjectController @Inject() (
    val db: CouchDB.Database,
    cnpHelper: ComputeNodeProjectHelper,
    mainController: Application) extends Controller with Utils {

  implicit class CNPHelper(cnp: ComputeNode.Project) {
    def makeActive(shouldBeActive: Boolean): Future[ComputeNode.Project] =
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
      case Accepts.Html() => mainController.main("projects")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val name = (json \ "name").as[String]
      val description = (json \ "description").as[Option[String]]
        .getOrElse("")
      val image = (json \ "image").as[String]
      val shouldBeActive = (json \ "active").as[Boolean]
      val response: Future[Result] =
        for {
          project <- projectDao.create(request.user, name, description, image)
          p <- cnpHelper.creator(project)
          cnProject <- if (shouldBeActive) p.start else Future.successful(p)
        } yield {
          Created(Json.obj(
            "id" -> project.id,
            "name" -> project.name,
            "description" -> project.description,
            "image" -> project.image,
            "active" -> cnProject.active
          ))
        }
      response.flatMap(_.withUpdatedJwt(request.user))
    }.getOrElse(Future.successful(BadRequest))
  }

  def list = Authenticated.async { implicit request =>
    projectPairs.map { pairs =>
      val user = request.user
      val json = JsArray(pairs.map { case (p, cnp) =>
          Json.obj(
            "id" -> p.id,
            "name" -> p.name,
            "description" -> p.description,
            "active" -> cnp.active
          )
        })
      Ok(json)
    }
  }

  def update(id: String) = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val shouldBeActive: Boolean = (json \ "active").as[Boolean]
      projectDao.get(id)
        .flatMap[Result] {
          case None =>
            Future.successful(NotFound)
          case Some(project) =>
            cnpHelper.resolver(project).flatMap {
              case None =>
                // TODO: Improve this handling
                Future.successful(NotFound)
              case Some(cnp) =>
                cnp.makeActive(shouldBeActive).map { updatedCnp =>
                  Ok(Json.obj(
                    "id" -> project.id,
                    "name" -> project.name,
                    "description" -> project.description,
                    "active" -> updatedCnp.active
                  ))
                }
            }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def delete(id: String) = Authenticated.async { implicit request =>
    projectDao.get(id)
      .flatMap[Result] {
        case None =>
          Future.successful(NotFound)
        case Some(project) =>
          cnpHelper.resolver(project).flatMap {
            case None =>
              // TODO: Improve this handling
              Future.successful(NotFound)
            case Some(cnp) =>
              project.delete.flatMap(_ => cnp.delete).map { _ => NoContent }
          }
      }
  }

  def checkNewName(name: String) = Authenticated.async { implicit request =>
    projectDao.list.map { projects =>
      if (projects.exists(p => p.name == name)) {
        Ok(Json.obj(
          "valid" -> false,
          "reason" -> "A project with that name already exists."
        ))
      } else {
        validateProjectName(name) match {
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

  def validateProjectName(name: String): Either[String, Unit] = {
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

  private def projectPairs(implicit request: AuthenticatedRequest[_]):
      Future[Seq[(Project, ComputeNode.Project)]] = {
    projectDao.list.flatMap { projects =>
      val userProjects = projects.filter(_.ownerIDs.contains(request.user.id))
      val r = cnpHelper.resolver // Use a single resolver instance
      Future.sequence(
        // For each project do a lookup with the resolver
        userProjects.map(r)
      ).map { results =>
        // Zip together project with result, then remove projects with missing
        // compute node projects.
        userProjects.zip(results).flatMap { pair =>
          pair._2.map(pair._1 -> _)
        }
      }
    }
  }

}