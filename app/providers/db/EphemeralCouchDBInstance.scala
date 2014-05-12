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
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, future}
import scala.concurrent.Await
import akka.util.Timeout

class EphemeralCouchDBInstance(implicit ec: ExecutionContext) extends ManagedCouchDBInstance {

  override lazy val baseDir = Files.createTempDirectory("couchdb-")

  override def shutdown {
    super.shutdown
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