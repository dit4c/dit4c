package providers

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector
import play.api.Plugin
import play.twirl.api.Html
import providers.auth._
import providers.db.CouchDB
import providers.db.CouchDBPlugin
import providers.auth.Identity
import scala.concurrent.ExecutionContext
import models.ComputeNodeContainerHelper
import models.ComputeNodeContainerHelperImpl

class InjectorPlugin(app: play.api.Application) extends Plugin {

  implicit val ec = play.api.libs.concurrent.Execution.defaultContext

  var injector: Option[Injector] = None

  override def onStart {
    injector = Some(
      Guice.createInjector(new AbstractModule {
        val appConfig = app.configuration

        lazy val authProviders = AuthProviders(
            RapidAAFAuthProvider(appConfig) ++
            GitHubProvider(appConfig) ++
            DummyProvider(appConfig))

        val dbName = "dit4c-highcommand"

        lazy val dbServerInstance = app.plugin[CouchDBPlugin].get.get

        // Make sure a database exists
        lazy val database = Await.result(
          dbServerInstance.databases(dbName).flatMap {
            case Some(db) => Future.successful(db)
            case None => dbServerInstance.databases.create(dbName)
          },
          1.minute)

        def configure {
          bind(classOf[AuthProviders]).toInstance(authProviders)
          bind(classOf[CouchDB.Database]).toInstance(database)
          bind(classOf[ExecutionContext]).toInstance(
              play.api.libs.concurrent.Execution.defaultContext)
          bind(classOf[ComputeNodeContainerHelper])
            .to(classOf[ComputeNodeContainerHelperImpl])
        }
      })
    )
  }

  override def onStop {
    injector = None
  }

}
