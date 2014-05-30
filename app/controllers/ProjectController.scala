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
      val name = (json \ "project" \ "name").as[String]
      val description = (json \ "project" \ "description").as[Option[String]]
        .getOrElse("")
      val shouldBeActive = (json \ "project" \ "active").as[Boolean]
      val response: Future[SimpleResult] =
        for {
          project <- projectDao.create(name, description)
          p <- cnpHelper.creator(project)
          cnProject <- if (shouldBeActive) p.start else Future.successful(p)
        } yield {
          Created(Json.obj(
            "project" -> Json.obj(
              "id" -> project.id,
              "name" -> project.name,
              "description" -> project.description,
              "active" -> cnProject.active
            )
          ))
        }
      response.flatMap(_.withUpdatedJwt)
    }.getOrElse(Future.successful(BadRequest))
  }

  def list = Authenticated.async { implicit request =>
    projectPairs.map { pairs =>
      val json = Json.obj(
        "project" -> JsArray(pairs.map { case (p, cnp) =>
          Json.obj(
            "id" -> p.id,
            "name" -> p.name,
            "description" -> p.description,
            "active" -> cnp.active
          )
        }))
      Ok(json)
    }
  }

  def update(id: String) = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val shouldBeActive: Boolean = (json \ "project" \ "active").as[Boolean]
      projectDao.get(id)
        .flatMap[SimpleResult] {
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
                    "project" -> Json.obj(
                      "id" -> project.id,
                      "name" -> project.name,
                      "description" -> project.description,
                      "active" -> updatedCnp.active
                    )
                  ))
                }
            }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def delete(id: String) = Authenticated.async { implicit request =>
    projectDao.get(id)
      .flatMap[SimpleResult] {
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

  private def projectPairs: Future[Seq[(Project, ComputeNode.Project)]] = {
    projectDao.list.flatMap { projects =>
      val r = cnpHelper.resolver // Use a single resolver instance
      Future.sequence(
        // For each project do a lookup with the resolver
        projects.map(r)
      ).map { results =>
        // Zip together project with result, then remove projects with missing
        // compute node projects.
        projects.zip(results).flatMap { pair =>
          pair._2.map(pair._1 -> _)
        }
      }
    }
  }

}