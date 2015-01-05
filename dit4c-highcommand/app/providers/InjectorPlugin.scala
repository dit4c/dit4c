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

        val couchDbViews: Map[String, ViewDoc] = {
          import views.js.models._
          Map(
            "access_tokens" -> ViewDoc(access_tokens().body, None),
            "all_by_type" -> ViewDoc(all_by_type().body, None),
            "user_identities" -> ViewDoc(user_identities().body, None)
          )
        }

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
            _ <- updateViews(design, couchDbViews)
          } yield db,
          1.minute)

        protected def updateViews(design: Design, views: Map[String, ViewDoc]) =
          for {
            designDoc <- design.getDesignDocument.flatMap {
              case Some(doc) => Future.successful(doc)
              case None => design.create
            }
            // Copy in view definitions
            updatedDoc = designDoc.copy(views = views).withRev(designDoc._rev)
            // Update view only if it will change
            _ <-
              if (updatedDoc == designDoc) Future.successful(())
              else design.db.saveDoc(updatedDoc)
          } yield design

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
