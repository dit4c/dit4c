package providers.db

import com.google.inject.Provider
import play.api.Plugin
import java.io.File
import java.util.UUID
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.ExecutionContext

class CouchDbPlugin(app: play.api.Application) extends Plugin {

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

  override def onStart {
    serverInstance // Make sure server initializes at app start
  }

  override def onStop {
    serverInstance.shutdown
  }

}