package providers.db

import scala.concurrent.{ExecutionContext, Future, future}
import java.nio.file._
import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.URL
import java.nio.charset.Charset
import play.api.Application

abstract class ManagedCouchDBInstance(implicit ec: ExecutionContext, app: Application) extends CouchDB.Instance {

  def baseDir: Path
  def desiredPort: Int = 0

  val (process, url) = startProcess

  def shutdown {
    process.destroy
  }

  protected def startProcess = {
    val dbDir = createDirIfMissing(baseDir.resolve("data"))
    val configFile = baseDir.resolve("config.ini");
    Files.write(configFile,
      s"""|[httpd]
          |port = $desiredPort
          |
          |[couchdb]
          |database_dir = $dbDir
          |view_index_dir = $dbDir
          |uri_file = ${baseDir}/couchdb.uri
          |
          |[log]
          |file = ${baseDir}/couch.log
          |""".stripMargin.getBytes)
    val uriFile = baseDir.resolve("couchdb.uri")
    Files.deleteIfExists(uriFile)
    import scala.sys.process.Process
    val process = (Process(s"couchdb -a $configFile") #> NullOutputStream).run
    Await.ready(uriFileCreated(baseDir), 60.seconds)
    (process, new URL(Files.readAllLines(uriFile, Charset.forName("UTF-8")).get(0)))
  }

  protected def createDirIfMissing(dir: Path): Path = {
    if (!Files.isDirectory(dir)) {
      Files.createDirectories(dir)
    }
    dir
  }

  protected def uriFileCreated(baseDir: Path): Future[Path] = Future {
    import java.nio.file._
    import java.nio.file.StandardWatchEventKinds._
    import scala.collection.JavaConversions._
    val watcher = FileSystems.getDefault.newWatchService
    val watchKey = baseDir.register(watcher, ENTRY_CREATE)
    // Stream (lazy sequence) of paths created
    def getPathsCreated: Stream[Path] = {
      // Poll watcher, read queued events, and reset to receive further events
      def paths = {
        val key = watcher.take
        try {
          key.pollEvents.toStream.map { event =>
            baseDir.resolve(event.context.asInstanceOf[Path].getFileName)
          }
        } finally {
          key.reset
        }
      }
      // Lazy recursion creates stream from successive watcher takes
      paths #::: getPathsCreated
    }
    try {
      // wait for file to be created
      getPathsCreated.filter(_.getFileName.endsWith("couchdb.uri")).head
    } finally {
      // close watchers
      watchKey.cancel
      watcher.close
    }
  }

  object NullOutputStream extends java.io.OutputStream {
    override def write(i: Int) = {}
  }

}