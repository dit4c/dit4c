package providers.db

import com.google.inject.Provider
import play.api.Plugin
import java.io.File
import java.util.UUID
import akka.actor.ActorSystem
import akka.util.Timeout

class CouchDbPlugin(app: play.api.Application) extends Plugin {

  implicit def system: ActorSystem = play.api.libs.concurrent.Akka.system(app)

  implicit val timeout: Timeout = Timeout(5000)

  lazy val log = play.api.Logger

  lazy val serverInstance: EphemeralCouchDbInstance =
    new EphemeralCouchDbInstance

  override def onStart {
    serverInstance
  }

  override def onStop {
    serverInstance.shutdown
  }

}