package providers.hipache

import providers.db.CouchDB
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import play.api.Plugin
import providers.InjectorPlugin
import akka.actor.ActorRef
import akka.actor.Props
import scala.concurrent.Await
import akka.pattern.ask
import akka.actor.Actor
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}
import models._
import net.nikore.etcd.EtcdClient

class HipachePlugin(app: play.api.Application) extends Plugin {

  implicit val timeout = new Timeout(30, TimeUnit.SECONDS)
  implicit lazy val ec = play.api.libs.concurrent.Execution.defaultContext
  lazy val system = play.api.libs.concurrent.Akka.system(app)

  def injector = app.plugin[InjectorPlugin].get.injector.get
  def db = injector.getInstance(classOf[CouchDB.Database])
  def containerResolver = injector.getInstance(classOf[ContainerResolver])

  def serverConfig: Option[Hipache.ServerConfig] =
    for {
      url <- app.configuration.getString("hipache.etcd.url")
      prefix = app.configuration
        .getString("hipache.etcd.prefix")
        .getOrElse("dit4c/hipache")
      safeUrl = url.replaceFirst("/$", "")
    } yield {
      Hipache.ServerConfig(
        new EtcdClient(safeUrl),
        prefix
      )
    }

  private var manager: Option[ActorRef] = None

  def client: Future[Option[HipacheClient]] = manager match {
    case None => Future.successful(None)
    case Some(mgr) => (mgr ? "client").map {
      case client: HipacheClient => Some(client)
      case _ => None
    }
  }

  override def enabled = serverConfig.isDefined

  override def onStart {
    manager = serverConfig.map { config =>
      system.actorOf(Props(classOf[HipacheManagementActor],
        config, db, containerResolver))
    }
  }

}

class HipacheManagementActor(
    config: Hipache.ServerConfig,
    db: CouchDB.Database,
    resolver: ContainerResolver) extends Actor {

  import context.dispatcher

  val log = akka.event.Logging(context.system, this)

  lazy val containerDao = new ContainerDAO(db)

  val tickInterval = new FiniteDuration(1, TimeUnit.HOURS)

  val tickSchedule = context.system.scheduler.schedule(
    Duration.Zero, tickInterval, self, "tick")

  val client = new HipacheClient(config)(context.system)

  def receive: Receive = {
    case "client" => {
      sender ! client
    }
    case "tick" => performMaintenance
  }

  private def performMaintenance: Future[_] = {
    log.debug("Starting Hipache maintenance routine.")
    for {
      currentMappings <- client.all
      computeNodeDao = new ComputeNodeDAO(db, new KeyDAO(db))
      containerDao = new ContainerDAO(db)
      computeNodes <- computeNodeDao.list
      containers <- containerDao.list
      putOps = containers.flatMap { c =>
        computeNodes.find(_.id == c.computeNodeId).map { node =>
          val frontend = resolver.asFrontend(c)
          currentMappings.get(frontend) match {
            case Some(b) if b == node.backend =>
              Future.successful(())
            case _ =>
              client.put(frontend, node.backend).map(_ =>())
          }
        }
      }
      containerExists = containers
        .map(resolver.asFrontend(_))
        .filter(resolver.isContainerFrontend)
        .toSet
        .contains _
      deleteOps = currentMappings.keys
        .filterNot(frontend => containerExists(frontend.name))
        .map(c => client.delete(c).map(_ => s"Deleted Hipache mapping for: $c"))
        .toSeq
      opsDone <- Future.sequence(putOps ++ deleteOps)
    } yield opsDone
  }
}
