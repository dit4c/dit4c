package providers

import scala.util.Try

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.Injector

import play.api.Plugin
import play.api.templates.Html
import providers.auth.AuthProvider
import providers.auth.CallbackResult
import providers.auth.RapidAAFAuthProvider
import providers.auth.RapidAAFAuthProviderConfig
import providers.db.CouchDBPlugin

class InjectorPlugin(app: play.api.Application) extends Plugin {

  var injector: Option[Injector] = None

  override def onStart {
    injector = Some(
      Guice.createInjector(new AbstractModule {
        val appConfig = app.configuration

        lazy val authProvider = appConfig.getConfig("rapidaaf").flatMap { c =>
          Try({
            val id = c.getString("id").getOrElse("RapidAAF")
            val url = new java.net.URL(c.getString("url").get)
            val key = c.getString("key").get
            val config = new RapidAAFAuthProviderConfig(id, url, key)
            new RapidAAFAuthProvider(config)
          }).toOption
        }.getOrElse {
          new AuthProvider {
            val errorMsg = "AuthProvider not configured"
            override def callbackHandler = { _ =>
              CallbackResult.Failure(errorMsg)
            }
            override def loginButton = Html(
              s"""<div class="alert alert-danger">$errorMsg</div>"""
            )
          }
        }

        def configure {
          bind(classOf[AuthProvider]).toInstance(authProvider)
        }
      })
    )
  }

  override def onStop {
    injector = None
  }

}