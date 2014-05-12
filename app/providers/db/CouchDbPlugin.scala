package providers.db

import com.google.inject.Provider
import play.api.Plugin
import java.io.File
import java.util.UUID
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class CouchDbPlugin(app: play.api.Application) extends Plugin with Provider[CouchDb.Instance] {

  implicit def ec: ExecutionContext = play.api.libs.concurrent.Execution.defaultContext
  implicit def system: ActorSystem = play.api.libs.concurrent.Akka.system(app)

  implicit val timeout: Timeout = Timeout(5000)

  lazy val log = play.api.Logger

  lazy val serverInstance: ManagedCouchDbInstance =
    if (app.configuration.getBoolean("couchdb.testing").getOrElse(false)) {
      new EphemeralCouchDbInstance
    } else {
      new PersistentCouchDbInstance("./db", 40000)
    }

  val dbName = "dit4c-highcommand"

  def get = serverInstance

  override def onStart {
    // Make sure a database exists
    serverInstance.databases(dbName).flatMap {
      case Some(db) => Future.successful(db)
      case None => serverInstance.databases.create(dbName)
    }
  }

  override def onStop {
    serverInstance.shutdown
  }

}