package providers.db

import gnieh.sohva.async.CouchClient
import com.google.inject.Provider
import play.api.Plugin
import gnieh.sohva.testing.CouchInstance
import java.io.File
import java.util.UUID

class CouchDbPlugin(app: play.api.Application) extends Plugin with Provider[CouchClient] {

  lazy val serverInstance: Option[CouchInstance] =
    if (app.configuration.getBoolean("couchdb.testing").getOrElse(false)) {
      Some(new CouchInstance(
          new File("/tmp/couchtest-"+UUID.randomUUID.toString), false, true))
    } else {
      None
    }

  lazy val client: CouchClient =
    serverInstance.map { server =>
      // TODO: Get couch client connected to server instance
      ???
    }.getOrElse(new CouchClient)

  override def get = client

  override def onStart {
    serverInstance.foreach(_.start)
  }

  override def onStop {
    try {
      client.shutdown
    } catch {
      case _: IllegalStateException => // Don't care
    }
    serverInstance.foreach(_.stop)
  }

}