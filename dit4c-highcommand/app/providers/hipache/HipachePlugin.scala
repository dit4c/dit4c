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
import scala.concurrent.duration.FiniteDuration
import models.ContainerDAO

class HipachePlugin(app: play.api.Application) extends Plugin {

  implicit val timeout = new Timeout(30, TimeUnit.SECONDS)
  implicit lazy val ec = play.api.libs.concurrent.Execution.defaultContext
  lazy val system = play.api.libs.concurrent.Akka.system(app)

  def injector = app.plugin[InjectorPlugin].get.injector.get
  def db = injector.getInstance(classOf[CouchDB.Database])

  def serverConfig: Option[Hipache.ServerConfig] =
    for {
      host <- app.configuration.getString("hipache.redis.host")
      port = app.configuration
        .getInt("hipache.redis.port")
        .getOrElse(6379)
      password = app.configuration.getString("hipache.redis.password")
      db = app.configuration.getInt("hipache.redis.db")
      prefix = app.configuration
        .getString("hipache.redis.prefix")
        .getOrElse("dit4c:hipache:")
    } yield Hipache.ServerConfig(
      redis.RedisServer(host, port, password, db),
      prefix
    )


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
      system.actorOf(Props(classOf[HipacheManagementActor], config, db))
    }
  }

}

class HipacheManagementActor(
    config: Hipache.ServerConfig,
    db: CouchDB.Database) extends Actor {

  import context.dispatcher

  val log = akka.event.Logging(context.system, this)

  lazy val containerDao = new ContainerDAO(db)

  val tickInterval = new FiniteDuration(1, TimeUnit.HOURS)

  val tickSchedule = context.system.scheduler.schedule(
    tickInterval, tickInterval, self, "tick")

  val client = new HipacheClient(config)(context.system)

  def receive: Receive = {
    case "client" => {
      sender ! client
    }
    case "tick" => performMaintenance
  }

  override def postStop = {
    client.disconnect
  }

  // TODO: Actually check mappings, rather than just printing them
  private def performMaintenance: Future[_] =
    for {
      map <- client.all
    } yield {
      val mappings = map.toSeq.sortBy(_._1.name).map { case (f, b) =>
        s"$f → $b"
      }.mkString("\n")
      log.info("Current Hipache mappings:\n"+mappings)
    }


}