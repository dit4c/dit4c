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
      val name: String = (json \ "project" \ "name").as[String]
      val shouldBeActive: Boolean = (json \ "project" \ "active").as[Boolean]
      val response: Future[SimpleResult] =
        for {
          nodes <- computeNodeDao.list
          node = nodes.head
          p <- node.projects.create(name)
          project <- if (shouldBeActive) p.start else Future.successful(p)
        } yield {
          Created(Json.obj(
            "project" -> Json.obj(
              "id" -> project.name,
              "name" -> project.name,
              "active" -> project.active
            )
          ))
        }
      response.flatMap(_.withUpdatedJwt)
    }.getOrElse(Future.successful(BadRequest))
  }

  def list = Action.async { implicit request =>
    computeNodeDao.list.flatMap { nodes =>
      Future.sequence(nodes.map(_.projects.list))
    }.map(_.flatten.toList.sortBy(_.name)).map { projects =>
      val json = Json.obj(
        "project" -> JsArray(projects.map { project =>
          Json.obj(
            "id" -> project.name,
            "name" -> project.name,
            "active" -> project.active
          )
        }))
      Ok(json)
    }
  }

  def update(id: String) = Action.async { implicit request =>
    request.body.asJson.map { json =>
      val shouldBeActive: Boolean = (json \ "project" \ "active").as[Boolean]
      computeNodeDao.list.flatMap { nodes =>
        Future.sequence(nodes.map(_.projects.get(id)))
      }.map(_.flatten.headOption).flatMap {
        case Some(project) if project.active != shouldBeActive =>
          val action = if (shouldBeActive) project.start else project.stop
          action.map { updatedProject =>
            Ok(Json.obj(
              "project" -> Json.obj(
                "id" -> updatedProject.name,
                "name" -> updatedProject.name,
                "active" -> updatedProject.active
              )
            ))
          }
        case Some(project) =>
          Future.successful(Ok(Json.obj(
            "project" -> Json.obj(
              "id" -> project.name,
              "name" -> project.name,
              "active" -> project.active
            )
          )))
        case None => Future.successful(NotFound)
      }
    }.getOrElse(Future.successful(BadRequest))
  }

  def delete(id: String) = Action.async { implicit request =>
    computeNodeDao.list.flatMap { nodes =>
      Future.sequence(nodes.map(_.projects.get(id)))
    }.map(_.flatten.headOption).flatMap {
      case Some(project) =>
        project.delete.map { _ => NoContent }
      case None => Future.successful(NotFound)
    }
  }

}