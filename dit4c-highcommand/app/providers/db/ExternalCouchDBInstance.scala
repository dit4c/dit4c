package providers.db

import scala.concurrent.ExecutionContext
import play.api.Application

class ExternalCouchDBInstance(
  val url: java.net.URL
  )(implicit ec: ExecutionContext, app: Application) extends CouchDB.Instance