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
import providers.db.{CouchDB, CouchDBPlugin}
import models.ViewManager
import providers.auth.Identity
import scala.concurrent.ExecutionContext
import gnieh.sohva.{DesignDoc,ViewDoc}
import gnieh.sohva.async._

class InjectorPlugin(app: play.api.Application) extends Plugin {

  implicit val ec = play.api.libs.concurrent.Execution.defaultContext

  lazy val log = play.api.Logger
  var injector: Option[Injector] = None

  override def onStart {
    injector = Some(
      Guice.createInjector(new AbstractModule {
        val appConfig = app.configuration

        lazy val authProviders = AuthProviders(
            RapidAAFAuthProvider(appConfig) ++
            GitHubProvider(appConfig) ++
            DummyProvider(appConfig))

        val dbName = app.configuration.getString("couchdb.database").get

        lazy val dbServerInstance = app.plugin[CouchDBPlugin].get.get

        // Make sure a database exists
        lazy val database = Await.result(
          for {
            maybeDb <- dbServerInstance.databases(dbName)
            db <- maybeDb match {
              case Some(db) => Future.successful(db)
              case None => dbServerInstance.databases.create(dbName)
            }
            design = db.asSohvaDb.design("main", "javascript")
            _ <- ViewManager.update(design)
          } yield db,
          1.minute)

        def configure {
          bind(classOf[play.api.Application]).toInstance(app)
          bind(classOf[AuthProviders]).toInstance(authProviders)
          bind(classOf[CouchDB.Database]).toInstance(database)
          bind(classOf[ExecutionContext]).toInstance(
              play.api.libs.concurrent.Execution.defaultContext)
        }
      })
    )
  }

  override def onStop {
    injector = None
  }

}
