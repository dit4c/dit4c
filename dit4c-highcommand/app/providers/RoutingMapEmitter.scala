package providers

import com.google.inject._
import providers.db.CouchDB
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Concurrent
import play.api.Environment
import gnieh.sohva.{Change, LastSeq}
import play.api.libs.json.Json
import models._
import scala.language.implicitConversions
import akka.agent.Agent
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Format
import scala.concurrent.Promise
import play.api.libs.json.JsPath
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink
import play.api.inject.ApplicationLifecycle
import akka.actor.Cancellable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import akka.actor.ActorSystem

class RoutingMapEmitter @Inject() @Singleton() (
    db: CouchDB.Database,
    changeFeed: ChangeFeed,
    computeNodeDao: ComputeNodeDAO,
    containerDao: ContainerDAO,
    containerResolver: ContainerResolver,
    system: ActorSystem,
    lifecycle: ApplicationLifecycle)(
        implicit executionContext: ExecutionContext,
        materializer: Materializer) {
  import RoutingMapEmitter._

  val (eventBus, channel) = Concurrent.broadcast[Event]

  case class CachedData(
      frontends: Map[Id, (Frontend, Id)],
      backends: Map[Id, Backend])

  val cachedData: Agent[Option[CachedData]] = Agent(None)

  // Monitoring "loop", which triggers a new monitoring step when the 
  // old finishes
  {
    var currentFuture = Future.successful(())
    val shutdown = Promise[Unit]()
    lifecycle.addStopHook { () =>
      shutdown.success(())
      currentFuture
    }
    new Thread(new Runnable {
      def run = {
        while (!shutdown.isCompleted) {
          Await.result({
            currentFuture = monitorChanges(shutdown.future).recoverWith {
              case PrematureCompletion =>
                println("Change feed closed early. Waiting...")
                akka.pattern.after(
                    1.second, system.scheduler)(Future.successful(()))
            }
            currentFuture
          }, Duration.Inf)
        }
      }
    }).start()
  }
  
  def monitorChanges(cancellation: Future[Unit]): Future[Unit] =
    for {
      (containers, containerChanges) <- containerDao.changes
      (computeNodes, computeNodeChanges) <- computeNodeDao.changes
      frontends =
        containers.map { c =>
          (c.id, (containerResolver.asFrontend(c), c.computeNodeId))
        }.toMap
      backends = computeNodes.map(cn => (cn.id, cn.backend)).toMap
      _ <- cachedData alterOff { _ =>
        Some(CachedData(frontends, backends))
      }
      updatedRoutes <- routesAfterPendingUpdates
      _ = updatedRoutes.foreach(rs => channel.push(ReplaceAllRoutes(rs)))
      subscriber = new InterruptSubscriber[Unit]
      computeNodeSource = computeNodeChanges
        .map {
          case ChangeFeed.Update(id, cn) =>
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
            ()
          case ChangeFeed.Deletion(id) =>
            // No need to do anything, as compute node deletion without container
            // deletion doesn't make any sense currently.
            ()
        }
      containerSource = containerChanges
        .map {
          case ChangeFeed.Update(id, c) =>
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
            ()
          case ChangeFeed.Deletion(id) =>
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
            ()
        }
      _ = {
        computeNodeSource.merge(containerSource, false).runWith(
          Sink.fromSubscriber(subscriber)
            .mapMaterializedValue(_ => subscriber.future))
        cancellation.onComplete(_ => subscriber.cancel)
      }
      finished <- subscriber.future
    } yield finished

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
  
  private class InterruptSubscriber[T]
      extends Subscriber[T] with Cancellable {
    private val p = Promise[Unit]()
    def future = p.future
    override def cancel = { p.trySuccess(()); true }
    override def isCancelled = p.isCompleted
    override def onError(t: Throwable) = p.tryFailure(t)
    override def onSubscribe(s: Subscription) = {
      p.future.onComplete(_ => s.cancel)
      s.request(Long.MaxValue)
    }
    override def onComplete = p.tryFailure(PrematureCompletion)
    override def onNext(t: T) = ()
  }
  
  private object PrematureCompletion
    extends Exception("Completed before cancellation")
  
}

object RoutingMapEmitter {

  type Id = String
  
  case class Frontend(
      name: String,
      domain: String)

  case class Backend(
      host: String,
      port: Int = 80,
      scheme: String = "http") {
    override def toString = s"$scheme://$host:$port"
  }

  case class Route(
      val frontend: Frontend,
      val backend: Backend)

  sealed trait Event
  case class SetRoute(route: Route) extends Event
  case class DeleteRoute(route: Route) extends Event
  case class ReplaceAllRoutes(routes: Set[Route]) extends Event

  implicit val routingBackendFormat: Format[RoutingMapEmitter.Backend] = {
    import play.api.libs.functional.syntax._
    (
      (JsPath \ "host").format[String] and
      (JsPath \ "port").format[Int] and
      (JsPath \ "scheme").format[String]
    )(RoutingMapEmitter.Backend.apply _, unlift(RoutingMapEmitter.Backend.unapply))
  }
}