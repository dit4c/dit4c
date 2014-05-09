package providers.db

import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.net.ServerSocket
import scala.util.Try
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.nio.file.FileVisitResult
import java.io.PrintWriter
import java.net.URL

class EphemeralCouchDbInstance extends CouchDbInstance {

  val baseDir = Files.createTempDirectory("couchdb-")
  val (process, url) = {
    val dbDir = Files.createDirectory(baseDir.resolve("data"))
    val configFile = baseDir.resolve("config.ini");
    Files.write(configFile,
      s"""|[httpd]
          |port = 0
          |
          |[couchdb]
          |database_dir = $dbDir
          |view_index_dir = $dbDir
          |uri_file = ${baseDir}/couchdb.uri
          |
          |[log]
          |file = ${baseDir}/couch.log
          |""".stripMargin.getBytes)
    import scala.sys.process.Process
    val process = Process(s"couchdb -a $configFile").run
    Thread.sleep(1000) // TODO: Fix this
    val url = Files.readAllLines(baseDir.resolve("couchdb.uri")).get(0)
    (process, new URL(url))
  }

  def host = url.getHost
  def port = url.getPort

  def shutdown {
    process.destroy
    recursivelyDelete(baseDir)
  }

  def recursivelyDelete(path: Path) =
    Files.walkFileTree(path, new DeletingFileVisitor)


  class DeletingFileVisitor extends SimpleFileVisitor[Path] {
    override def visitFile(file: Path, attrs: BasicFileAttributes) = {
      if (attrs.isRegularFile) {
        Files.delete(file)
      }
      FileVisitResult.CONTINUE
    }

    override def postVisitDirectory(dir: Path, ioe: IOException) = {
      Files.delete(dir)
      FileVisitResult.CONTINUE
    }
  }

}