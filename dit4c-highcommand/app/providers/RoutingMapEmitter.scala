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
import scala.concurrent.Promise

class RoutingMapEmitter @Inject() @Singleton() (
    changeFeed: ChangeFeed,
    computeNodeDao: ComputeNodeDAO,
    containerDao: ContainerDAO,
    containerResolver: ContainerResolver)(
        implicit executionContext: ExecutionContext) {
  import RoutingMapEmitter._
  import Hipache.{Frontend, Backend}

  val (eventBus, channel) = Concurrent.broadcast[Event]

  case class CachedData(
      frontends: Map[Id, (Frontend, Id)],
      backends: Map[Id, Backend])


  val cachedData: Agent[Option[CachedData]] = Agent(None)

  for {
    containers <- containerDao.list
    frontends =
      containers.map { c =>
        (c.id, (containerResolver.asFrontend(c), c.computeNodeId))
      }.toMap
    computeNodes <- computeNodeDao.list
    backends = computeNodes.map(cn => (cn.id, cn.backend)).toMap
    _ <- cachedData alterOff { _ =>
      Some(CachedData(frontends, backends))
    }
    updatedRoutes <- routesAfterPendingUpdates
  } yield updatedRoutes.foreach(rs => channel.push(ReplaceAllRoutes(rs)))

  changeFeed.changes |>>> Iteratee.foreach { change =>
    import ChangeFeed._
    change match {
      case Update(id, doc) =>
        (doc \ "type").asOpt[String] match {
          case Some("ComputeNode") =>
            computeNodeDao.fromJson(doc).foreach { cn =>
              for {
                data <- updateCache { d =>
                  d.copy(backends = {
                    d.backends + ((id, cn.backend))
                  })
                }
              } yield {
                data.toIterable
                  .flatMap(_.frontends.toIterable)
                  .filter { case (_, (_, backendId)) => backendId == id }
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
                data <- updateCache { d =>
                  d.copy(frontends = {
                    d.frontends + ((c.id, (frontend, c.computeNodeId)))
                  })
                }
              } yield {
                data.map(_.backends).foreach { bs =>
                  channel.push(SetRoute(Route(frontend, bs(c.computeNodeId))))
                }
              }
            }
          case _ => // Ignore
        }
      case Deletion(id) =>
        // We only check frontends, as compute node deletion without container
        // deletion doesn't make any sense currently.
        val pDeletedRoute = Promise[Option[Route]]()
        for {
          _ <- updateCache { d =>
            d.frontends.get(id) match {
              case Some((frontend, backendId)) =>
                val route = Route(frontend, d.backends(backendId))
                pDeletedRoute.success(Some(route))
                d.copy(frontends = {
                  d.frontends - id
                })
              case None =>
                pDeletedRoute.success(None)
                d
            }
          }
          deletedRoute <- pDeletedRoute.future
        } yield {
          deletedRoute.foreach { r =>
            channel.push(DeleteRoute(r))
          }
        }
    }


  }


  def currentRoutes(data: CachedData): Set[Route] =
    data.frontends.values.map {
      case (frontend, backendId) =>
        Route(frontend, data.backends(backendId))
    }.toSet

  def routesNow: Option[Set[Route]] = cachedData.get.map(currentRoutes)

  def routesAfterPendingUpdates: Future[Option[Set[Route]]] =
    cachedData.future.map {
      _.map(currentRoutes)
    }

  def newFeed: Enumerator[Event] = {
    Enumerator.enumerate[Event](routesNow.map(ReplaceAllRoutes(_))) andThen
      eventBus
  }

  private def updateCache(
      f: CachedData => CachedData): Future[Option[CachedData]] =
    cachedData alterOff {
      _.map(f)
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