package dit4c.switchboard

import java.nio.file.Files
import scala.collection.JavaConversions._
import scala.sys.process.Process
import scala.util._
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.io.IOException
import com.typesafe.scalalogging.LazyLogging
import scala.sys.process.ProcessLogger
import java.util.concurrent.ConcurrentHashMap
import com.samskivert.mustache.Template

class NginxInstance(
    baseDomain: Option[String],
    port: Int,
    tlsConfig: Option[TlsConfig],
    extraMainConfig: Option[String],
    extraVHostConfig: Option[String]) extends LazyLogging {

  val config =
    NginxConfig(baseDomain, port, tlsConfig, extraMainConfig, extraVHostConfig)

  val nginxPath: String =
    try {
      Process("which nginx").!!.stripLineEnd
    } catch {
      case e: Throwable =>
        throw new Exception("Nginx binary not found in PATH")
    }
  protected def pLog = ProcessLogger(logger.debug(_), logger.debug(_))
  lazy val nginxProcess: Process =
    Process(s"$nginxPath -c ${config.mainConfig}").run(pLog)

  def replaceAllRoutes(routes: Seq[Route]) = reloadAfter {
    config.replaceAllRoutes(routes)
  }

  def setRoute(route: Route) = reloadAfter {
    config.setRoute(route)
  }

  def deleteRoute(route: Route) = reloadAfter {
    config.deleteRoute(route)
  }

  def shutdown = {
    nginxProcess.destroy
    config.cleanup
  }

  // Guard against instance not being shutdown, as there's no valid case where
  // it should be left running.
  sys.addShutdownHook(shutdown)

  protected def reloadAfter(f: => Unit) {
    f
    nginxProcess
    val p = Process(s"$nginxPath -c ${config.mainConfig} -s reload").run(pLog)
    if (p.exitValue != 0) {
      throw new RuntimeException(s"Reload failed. Exit code: ${p.exitValue}")
    }
  }

}
