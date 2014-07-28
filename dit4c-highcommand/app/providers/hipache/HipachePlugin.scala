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

  def client: Future[Option[ActorRef]] = manager match {
    case None => Future.successful(None)
    case Some(mgr) => (mgr ? "client").map {
      case actorRef: ActorRef => Some(actorRef)
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

  val client: ActorRef = context.actorOf(
      Props(classOf[HipacheActor], config),
      name = "hipacheClient")

  def receive: Receive = {
    case "client" => {
      sender ! client
    }
    case "tick" => performMaintenance
  }

  private def performMaintenance: Future[_] = Future {
    // TODO: Implement later
  }


}