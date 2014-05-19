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
    db: CouchDB.Database,
    mainController: Application) extends Controller {

  implicit def ec: ExecutionContext =
    play.api.libs.concurrent.Execution.defaultContext

  def index = Action.async { implicit request =>
    render.async {
      case Accepts.Html() => mainController.main("projects")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create = Action.async { implicit request =>
    request.body.asJson.map { json =>
      val name: String = (json \ "project" \ "name").as[String]
      for {
        nodes <- computeNodeDao.list
        node = nodes.head
        project <- node.projects.create(name)
      } yield {
        Created(Json.obj(
          "id" -> project.name,
          "name" -> project.name,
          "active" -> project.active
        ))
      }
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

  private lazy val computeNodeDao = new ComputeNodeDAO(db)

}