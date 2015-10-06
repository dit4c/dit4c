package providers

import javax.inject.Inject
import models.{KeyDAO, Key}
import providers.db.CouchDB
import scala.concurrent.Future
import scala.concurrent.Await
import org.joda.time.{DateTime, Period}
import scala.concurrent.duration.{Duration, FiniteDuration}
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import play.api.Environment
import play.api.Configuration
import com.google.inject.AbstractModule
import akka.actor.ActorSystem
import javax.inject.Singleton
import com.google.inject.Provides

class KeyManagementPlugin(
    environment: Environment,
    configuration: Configuration) extends AbstractModule {

  @Singleton @Provides def keyManagementConfig = KeyManagementConfig(
    configuration
      .getString("application.baseUrl")
      .getOrElse("DIT4C"),
    configuration
      .getInt("keys.length")
      .getOrElse(1024),
    Period.hours(3),
    Period.hours(24),
    Period.days(7)
  )

  override def configure {
    if (enabled) {
      bind(classOf[KeyManager]).asEagerSingleton()
    }
  }

  private def enabled = configuration.getBoolean("keys.manage").getOrElse(true)
}

@Singleton
class KeyManager @Inject() (
    config: KeyManagementConfig,
    system: ActorSystem,
    db: CouchDB.Database) {

  implicit val timeout = new Timeout(30, TimeUnit.SECONDS)

  private val actor = {
    val m = system.actorOf(Props(classOf[KeyManagementActor], config, db))
    Await.result(m ? "waitOnStart", timeout.duration)
    m
  }

}


case class KeyManagementConfig(
  namespace: String,
  keyLength: Int,
  creationWait: Period,
  retirementAge: Period,
  deletionAge: Period
)

class KeyManagementActor(
    config: KeyManagementConfig,
    db: CouchDB.Database) extends Actor {

  import context.dispatcher

  val log = akka.event.Logging(context.system, this)

  lazy val keyDao = new KeyDAO(db)

  val tickInterval = new FiniteDuration(1, TimeUnit.HOURS)

  val tickSchedule = context.system.scheduler.schedule(
    tickInterval, tickInterval, self, "tick")

  def receive: Receive = {
    case "waitOnStart" => {
      val replyTo = sender
      performMaintenance.map(_ => replyTo ! "done")
    }
    case "tick" => performMaintenance
  }

  private def performMaintenance: Future[Seq[Key]] =
    keyDao.list
      .flatMap(createKeyIfRequired(_))
      .flatMap(retireOldKeys(_))
      .flatMap(deleteOldKeys(_))

  /**
   * Create a new key if all non-retired keys are older than the creation age.
   */
  def createKeyIfRequired(keys: Seq[Key]): Future[Seq[Key]] =
    if (keys.filter(!_.retired).forall(_.isOlderThan(config.creationWait)))
      createNewKey.flatMap(_ => keyDao.list)
    else
      Future.successful(keys)

  /**
   * Retire keys past the retirement age.
   */
  def retireOldKeys(keys: Seq[Key]): Future[Seq[Key]] =
    conditionalKeyAction(keys,
        (key: Key) => !key.retired && key.isOlderThan(config.retirementAge),
        retireKey)

  /**
   * Delete keys past the deletion age.
   */
  def deleteOldKeys(keys: Seq[Key]): Future[Seq[Key]] =
    conditionalKeyAction(keys,
        (key: Key) => key.retired && key.isOlderThan(config.deletionAge),
        deleteKey)

  private def conditionalKeyAction(
      keys: Seq[Key],
      cond: Key => Boolean,
      action: Key => Future[Any]): Future[Seq[Key]] =
    keys.filter(cond) match {
      case Nil => Future.successful(keys)
      case keysForAction =>
        Future.sequence(keysForAction.map(k => action(k)))
          .flatMap(_ => keyDao.list)
    }

  implicit class KeyHelper(key: Key) {
    def isOlderThan(period: Period) =
      key.createdAt.plus(period).isBefore(DateTime.now)
  }

  private def createNewKey =
    keyDao.create(config.namespace, config.keyLength).map { key =>
      log.info(s"Created new key: ${key.publicId}")
    }

  private def retireKey(key: Key) = key.retire.map { key =>
    log.info(s"Retiring key: ${key.publicId}")
  }

  private def deleteKey(key: Key) = key.delete.map { _ =>
    log.info(s"Deleted key: ${key.publicId}")
  }

}
