package providers.db

import scala.concurrent.ExecutionContext
import java.nio.file.{Files,Paths}
import play.api.Application

class PersistentCouchDBInstance(
    dir: String,
    specifiedPort: Int)(implicit ec: ExecutionContext, app: Application)
  extends ManagedCouchDBInstance {

  def log = play.api.Logger

  override lazy val baseDir = createDirIfMissing(Paths.get(dir))
  override lazy val desiredPort = specifiedPort

  override protected def startProcess = {
    val (process, url) = super.startProcess
    log.info(s"CouchDB started: $url")
    (process, url)
  }

}