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

class ComputeNodeController @Inject() (
    val db: CouchDB.Database,
    mainController: Application) extends Controller with Utils {

  import Hipache.hipacheBackendFormat

  def index: Action[AnyContent] = Action.async { implicit request =>
    render.async {
      //case Accepts.Html() => mainController.main("containers")(request)
      case Accepts.Json() => list(request)
    }
  }

  def create: Action[AnyContent] = Authenticated.async { implicit request =>
    request.body.asJson.map { json =>
      val name = (json \ "name").as[String]
      val managementUrl = (json \ "managementUrl").as[String]
      val backend = (json \ "backend").as[Hipache.Backend]

      // Check this is a new server
      val fServerId: Future[Either[String, String]] =
        for {
          serverId <- MachineShop.fetchServerId(managementUrl)
              .map(Some(_))
              .fallbackTo(Future.successful(None))
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
        case Left(msg) => Future.successful(BadRequest(msg))
        case Right(serverId) =>
          for {
            node <- computeNodeDao.create(name, serverId, managementUrl, backend)
          } yield {
            Created(Json.obj(
              "id" -> node.id,
              "name" -> node.name,
              "managementUrl" -> node.managementUrl,
              "backend" -> node.backend
            ))
          }
      }
    }.getOrElse(Future.successful(BadRequest("Request must be JSON")))
  }

  def list: Action[AnyContent] = Authenticated.async { implicit request =>
    computeNodeDao.list map { nodes =>
      val json = JsArray(nodes.map { node =>
          Json.obj(
            "id" -> node.id,
            "name" -> node.name
          )
        })
      Ok(json)
    }
  }

  def update(id: String): Action[AnyContent] = ???

  def delete(id: String): Action[AnyContent] = ???

}