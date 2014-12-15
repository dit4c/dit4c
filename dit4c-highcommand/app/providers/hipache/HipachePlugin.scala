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

  def baseDomain =
    app.configuration.getString("application.baseUrl")
      .map(new java.net.URI(_))
      .map(_.getHost)

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
        config, db, baseDomain))
    }
  }

}

class HipacheManagementActor(
    config: Hipache.ServerConfig,
    db: CouchDB.Database,
    baseDomain: Option[String]) extends Actor {

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

  // TODO: Actually check mappings, rather than just printing them
  private def performMaintenance: Future[_] =
    for {
      map <- client.all
      computeNodeDao = new ComputeNodeDAO(db, new KeyDAO(db))
      containerDao = new ContainerDAO(db)
      computeNodes <- computeNodeDao.list
      containers <- containerDao.list
    } yield {
      val mappings = map.toSeq.sortBy(_._1.name).map { case (f, b) =>
        s"${f.domain} → $b"
      }.mkString("\n")
      log.info("Current Hipache mappings:\n"+mappings)
      val putOps: Seq[Future[String]] = containers.flatMap { c =>
        computeNodes.find(_.id == c.computeNodeId).map { node =>
          baseDomain match {
            case Some(domain) =>
              val frontend = Hipache.Frontend(c.name, s"${c.name}.${domain}")
              map.get(frontend) match {
                case Some(b) if b == node.backend =>
                  Future.successful {
                    s"Confirmed Hipache mapping of ${c.name} to ${node.name}."
                  }
                case _ =>
                  client.put(frontend, node.backend).map { _ =>
                    s"Created Hipache mapping of ${c.name} to ${node.name}."
                  }
              }
            case None =>
              Future.successful {
                s"Unable to check Hipache Mapping of ${c.name}. App domain unknown."
              }
          }
        }
      }
      val containerExists = containers.map(_.name).toSet.contains _
      val deleteOps: Seq[Future[String]] = map.keys
        .filterNot(frontend => containerExists(frontend.name))
        .map(c => client.delete(c).map(_ => s"Deleted Hipache mapping for: $c"))
        .toSeq
      (putOps ++ deleteOps).foreach { op =>
        op.foreach(log.info(_))
      }
    }

}
