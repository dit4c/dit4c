package providers.db

import scala.concurrent.ExecutionContext
import play.api.Application
import akka.actor.ActorSystem

class ExternalCouchDBInstance(
  val url: java.net.URL
  )(implicit ec: ExecutionContext, system: ActorSystem) extends CouchDB.Instance