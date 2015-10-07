package dit4c.switchboard

import java.nio.file.Files
import scala.sys.process.Process
import scala.util._
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.io.IOException
import org.fusesource.scalate.TemplateEngine
import com.typesafe.scalalogging.LazyLogging
import scala.sys.process.ProcessLogger

class NginxInstance(
    baseDomain: Option[String],
    port: Int,
    tlsConfig: Option[TlsConfig],
    extraMainConfig: Option[String],
    extraVHostConfig: Option[String]) extends LazyLogging {

  val baseDir = Files.createTempDirectory("nginx-")
  val cacheDir = Files.createDirectory(baseDir.resolve("cache"))
  val configDir = Files.createDirectory(baseDir.resolve("conf"))
  val proxyDir = Files.createDirectory(baseDir.resolve("proxy"))
  val tmpDir = Files.createDirectory(baseDir.resolve("tmp"))
  val vhostDir = Files.createDirectory(configDir.resolve("vhost.d"))
  val mainConfig = configDir.resolve("nginx.conf")

  val nginxPath: String =
    try {
      Process("which nginx").!!.stripLineEnd
    } catch {
      case e: Throwable =>
        throw new Exception("Nginx binary not found in PATH")
    }

  private val engine = new TemplateEngine

  writeFile(mainConfig) {
    implicit def path2str(p: Path) = p.toAbsolutePath.toString
    engine.layout("/nginx_main.tmpl.mustache",
      Map[String,Any](
        "basedir" -> baseDir,
        "cachedir" -> cacheDir,
        "proxydir" -> proxyDir,
        "tmpdir" -> tmpDir,
        "vhostdir" -> vhostDir,
        "pidfile" -> baseDir.resolve("nginx.pid"),
        "port" -> port.toString
      ) ++ baseDomain.map(d => Map("domain" -> d))
        ++ tlsConfig.map { c =>
          Map("tls" -> Map(
              "key" -> c.keyFile.getAbsolutePath,
              "certificate" -> c.certificateFile.getAbsolutePath))
        }
        ++ extraMainConfig.map { c =>
          Map("extraconf" -> c)
        }
    )
  }

  protected def pLog = ProcessLogger(logger.debug(_), logger.debug(_))
  val nginxProcess: Process = Process(s"$nginxPath -c $mainConfig").run(pLog)

  def replaceAllRoutes(routes: Seq[Route]) = reloadAfter {
    recursivelyDelete(vhostDir)
    Files.createDirectory(vhostDir)
    logger.info("Updating entire route set:")
    routes.foreach { route =>
      logger.info(route.domain)
      writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
    }
  }

  def setRoute(route: Route) = reloadAfter {
    logger.info(s"Setting route: ${route.domain}")
    writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
  }

  def deleteRoute(route: Route) = reloadAfter {
    logger.info(s"Deleting route: ${route.domain}")
    Files.delete(vhostDir.resolve(route.domain+".conf"))
  }

  def shutdown = {
    nginxProcess.destroy
    recursivelyDelete(baseDir)
  }

  // Guard against instance not being shutdown, as there's no valid case where
  // it should be left running.
  sys.addShutdownHook(shutdown)

  protected def reloadAfter(f: => Unit) {
    f
    Process(s"$nginxPath -c $mainConfig -s reload").run(pLog)
  }

  protected def vhostTmpl(route: Route) =
    engine.layout("/nginx_vhost.tmpl.mustache",
      Map(
        "https" -> tlsConfig.isDefined,
        "port" -> port.toString,
        "domain" -> route.domain,
        "headers" -> route.headers.map {
          case (k, v) => Map("name" -> k, "value" -> v)
        },
        "upstream" -> Map(
          "scheme" -> route.upstream.scheme,
          "host" -> route.upstream.host,
          "port" -> route.upstream.port.toString
        )
      ) ++ extraVHostConfig.map { c =>
        Map("extraconf" -> c)
      }
    )

  protected def writeFile(f: Path)(content: String): Path =
    Files.write(f, content.getBytes("utf-8"))

  protected def recursivelyDelete(path: Path) {
    if (Files.exists(path)) {
      Files.walkFileTree(path, new DeletingFileVisitor)
    }
  }

  implicit private def optionMapToMap[A,B](om: Option[Map[A,B]]): Map[A,B] =
    om.getOrElse(Map.empty[A,B])

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
