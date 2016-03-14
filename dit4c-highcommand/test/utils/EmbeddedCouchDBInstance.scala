package utils

import providers.db.CouchDB
import scala.concurrent.ExecutionContext
import akka.actor.ActorSystem
import com.couchbase.lite.Manager
import com.couchbase.lite.listener.LiteListener
import com.couchbase.lite.listener.Credentials
import com.couchbase.lite.JavaContext
import java.util.Properties
import Acme.Serve.Serve
import com.couchbase.lite.javascript.JavaScriptViewCompiler
import scala.util.Random

class EmbeddedCouchDBInstance(implicit ec: ExecutionContext, system: ActorSystem)
    extends CouchDB.Instance {

  protected val localBindAddress = "127.0.0.1"

  com.couchbase.lite.View.setCompiler(new JavaScriptViewCompiler())

  val (closeHandler, url) = startCBL

  override def disconnect = {
    super.disconnect
    closeHandler()
  }

  protected def startCBL: (() => Unit, java.net.URL) = {
    val context = new JavaContext(Random.alphanumeric.take(20).mkString(""))
    val manager = new Manager(context, Manager.DEFAULT_OPTIONS)
    manager.setStorageType(Manager.FORESTDB_STORAGE)
    val properties = new Properties()
    properties.put(Serve.ARG_BINDADDRESS, localBindAddress)
    val listener = new LiteListener(
        manager, randomPort, new Credentials("",""), properties)
    (new Thread(listener)).start
    val url = new java.net.URL(
        "http", localBindAddress, listener.getListenPort, "")
    println(url)
    val closeHandler = { () =>
      listener.stop()
      manager.close()
    }
    (closeHandler, url)
  }

  private def randomPort = 40000 + Random.nextInt(1000)

}