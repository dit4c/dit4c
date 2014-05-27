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
    mainController: Application) extends Controller with Utils {

  def index = Action.async { implicit request =>
    render.async {
      case Accepts.Html() => mainController.main("projects")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create = Action.async { implicit request =>
    request.body.asJson.map { json =>
      val name = (json \ "project" \ "name").as[String]
      val description = (json \ "project" \ "description").as[Option[String]]
        .getOrElse("")
      val shouldBeActive = (json \ "project" \ "active").as[Boolean]
      val response: Future[SimpleResult] =
        for {
          project <- projectDao.create(name, description)
          nodes <- computeNodeDao.list
          node = nodes.head
          p <- node.projects.create(name)
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

  def list = Action.async { implicit request =>
    for {
      projects <- projectDao.list
      cnpm <- futureComputeNodeProjectMap
      projectPairs = projects.map { project =>
        (project, cnpm(project.name))
      }
    } yield {
      val json = Json.obj(
        "project" -> JsArray(projectPairs.map { case (p, cnp) =>
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

  def update(id: String) = Action.async { implicit request =>
    request.body.asJson.map { json =>
      val shouldBeActive: Boolean = (json \ "project" \ "active").as[Boolean]
      projectDao.get(id)
        .flatMap[SimpleResult] {
          case None =>
            Future.successful(NotFound)
          case Some(project) =>
            futureComputeNodeProjectMap.flatMap { cnpm =>
              cnpm.get(project.name) match {
                case None =>
                  // TODO: Improve this handling
                  Future.successful(NotFound)
                case Some(cnp) =>
                  val action =
                    if (cnp.active != shouldBeActive)
                      if (shouldBeActive)
                        cnp.start
                      else
                        cnp.stop
                    else
                      Future.successful(cnp)
                  action.map { updatedCnp =>
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
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def delete(id: String) = Action.async { implicit request =>
    projectDao.get(id)
      .flatMap[SimpleResult] {
        case None =>
          Future.successful(NotFound)
        case Some(project) =>
          futureComputeNodeProjectMap.flatMap { cnpm =>
            cnpm.get(project.name) match {
              case None =>
                // TODO: Improve this handling
                Future.successful(NotFound)
              case Some(cnp) =>
                project.delete.flatMap(_ => cnp.delete).map { _ => NoContent }
            }
          }
      }
  }

  def futureComputeNodeProjectMap =
      computeNodeDao.list
        .flatMap(nodes => Future.sequence(nodes.map(_.projects.list)))
        .map(_.flatten.toList.sortBy(_.name))
        .map(_.map(cnp => (cnp.name -> cnp)).toMap)

}