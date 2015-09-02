package providers

import com.google.inject._
import providers.db.CouchDB
import play.api.libs.iteratee.Enumerator
import providers.hipache.Hipache
import play.api.libs.iteratee.Concurrent
import play.api.Environment
import gnieh.sohva.{Change, LastSeq}
import play.api.libs.json.Json
import models._
import scala.language.implicitConversions
import akka.agent.Agent
import providers.hipache.ContainerResolver
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Format

class RoutingMapEmitter @Inject() (
    changeFeed: ChangeFeed,
    computeNodeDao: ComputeNodeDAO,
    containerDao: ContainerDAO,
    containerResolver: ContainerResolver)(
        implicit executionContext: ExecutionContext) {
  import RoutingMapEmitter._
  import Hipache.{Frontend, Backend}

  val (eventBus, channel) = Concurrent.broadcast[Event]

  val frontends: Agent[Map[Id, (Frontend, Id)]] = Agent(Map.empty)
  val backends: Agent[Map[Id, Backend]] = Agent(Map.empty)

  for {
    containers <- containerDao.list
    _ <- frontends alterOff { _ =>
      containers.map { c =>
        (c.id, (containerResolver.asFrontend(c), c.computeNodeId))
      }.toMap
    }
    computeNodes <- computeNodeDao.list
    _ <- backends alterOff { _ =>
      computeNodes.map(cn => (cn.id, cn.backend)).toMap
    }
    routes <- currentRoutes
  } yield channel.push(ReplaceAllRoutes(routes))

  changeFeed.changes |>>> Iteratee.foreach { change =>
    import ChangeFeed._
    change match {
      case Update(id, doc) =>
        (doc \ "type").asOpt[String] match {
          case Some("ComputeNode") =>
            computeNodeDao.fromJson(doc).foreach { cn =>
              for {
                fs <- frontends.future
                _ <- backends.alter { _ + ((id, cn.backend)) }
              } yield {
                fs.filter { case (_, (_, backendId)) => backendId == id }
                  .foreach {
                    case (_, (frontend, _)) =>
                      channel.push(SetRoute(Route(frontend, cn.backend)))
                  }
              }
            }
          case Some("Container") =>
            containerDao.fromJson(doc).foreach { c =>
              val frontend = containerResolver.asFrontend(c)
              for {
                _ <- frontends.alter { _ + ((c.id, (frontend, c.computeNodeId))) }
                bs <- backends.future
              } yield {
                channel.push(SetRoute(Route(frontend, bs(c.computeNodeId))))
              }
            }
          case _ => // Ignore
        }
      case Deletion(id) =>
        // We only check frontends, as compute node deletion without container
        // deletion doesn't make any sense currently.
        frontends.future.foreach { fs =>
          fs.get(id).foreach {
            case (frontend, backendId) =>
              for {
                _ <- frontends.alter(_ - id)
                bs <- backends.future
              } yield {
                channel.push(DeleteRoute(Route(frontend, bs(backendId))))
              }
          }
        }
    }


  }


  def currentRoutes: Future[Set[Route]] =
    for {
      fs <- frontends.future
      bs <- backends.future
    } yield {
      fs.values.map(v => Route(v._1, bs(v._2))).toSet
    }


  def newFeed: Enumerator[Event] = Enumerator.flatten {
    for {
      routes <- currentRoutes
    } yield {
      println(routes)
      Enumerator.enumerate[Event](Some(ReplaceAllRoutes(routes))) andThen
        eventBus
    }
  }

}

object RoutingMapEmitter {

  type Id = String

  case class Route(
      val frontend: Hipache.Frontend,
      val backend: Hipache.Backend)

  sealed trait Event
  case class SetRoute(route: Route) extends Event
  case class DeleteRoute(route: Route) extends Event
  case class ReplaceAllRoutes(routes: Set[Route]) extends Event

}