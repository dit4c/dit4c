package testing

import scala.util.Random
import play.api.inject.guice.GuiceApplicationBuilder
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import com.google.inject.Provides
import javax.inject.Singleton
import scala.concurrent.Future
import com.google.inject.AbstractModule
import scala.concurrent.ExecutionContext
import providers.db.EphemeralCouchDBInstance

object TestUtils {

  def fakeApp = new GuiceApplicationBuilder()
      .configure(Map(
          "application.baseUrl" -> "http://localhost.localdomain/",
          "keys.manage" -> false,
          "keys.length" -> 512,
          "rapidaaf" -> Map(
              "id"   -> "RapidAAF",
              "url"  -> "http://example.test/",
              "key"  -> Random.nextString(32)
          )
      ))
      .overrides(new AbstractModule {
        @Singleton @Provides def dbServerInstance(
            lifecycle: ApplicationLifecycle)(
            implicit ec: ExecutionContext,
              system: ActorSystem): CouchDB.Instance = {
          val instance = new EphemeralCouchDBInstance
          lifecycle.addStopHook { () =>
            instance.disconnect
            Future.successful(())
          }
          instance
        }
        def configure = {}
      })
      .build
}