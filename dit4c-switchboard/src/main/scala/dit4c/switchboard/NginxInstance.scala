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

class NginxInstance(baseDomain: Option[String], port: Int) {

  val baseDir = Files.createTempDirectory("nginx-")
  val cacheDir = Files.createDirectory(baseDir.resolve("cache"))
  val configDir = Files.createDirectory(baseDir.resolve("conf"))
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
      Map[String,String](
        "basedir" -> baseDir,
        "cachedir" -> cacheDir,
        "vhostdir" -> vhostDir,
        "pidfile" -> baseDir.resolve("nginx.pid"),
        "port" -> port.toString
      ) ++ baseDomain.map(d => Map("domain" -> d)).getOrElse(Map.empty)
    )
  }

  val nginxProcess: Process = Process(s"$nginxPath -c $mainConfig").run

  def replaceAllRoutes(routes: Traversable[Route]) = reloadAfter {
    recursivelyDelete(vhostDir)
    Files.createDirectory(vhostDir)
    routes.foreach { route =>
      println(route)
      writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
    }
  }

  def setRoute(route: Route) = reloadAfter {
    writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
  }

  def deleteRoute(route: Route) = reloadAfter {
    Files.delete(vhostDir.resolve(route.domain+".conf"))
  }

  def shutdown = {
    nginxProcess.destroy
    recursivelyDelete(baseDir)
  }

  private def reloadAfter(f: => Unit) {
    f
    Process(s"$nginxPath -c $mainConfig -s reload").run
  }

  private def vhostTmpl(route: Route) =
    engine.layout("/nginx_vhost.tmpl.mustache",
      Map(
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
      )
    )

  private def writeFile(f: Path)(content: String): Path =
    Files.write(f, content.getBytes("utf-8"))

  private def recursivelyDelete(path: Path) =
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