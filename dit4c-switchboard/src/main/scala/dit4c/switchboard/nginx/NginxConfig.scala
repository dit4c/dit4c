package dit4c.switchboard.nginx

import scala.collection.JavaConversions._
import java.nio.file._
import scala.sys.process.ProcessLogger
import com.typesafe.scalalogging.LazyLogging
import com.samskivert.mustache._
import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try
import dit4c.switchboard.Route
import dit4c.switchboard.TlsConfig

class NginxConfig(
    val baseDir: Path,
    val baseDomain: Option[String],
    val port: Int,
    val tlsConfig: Option[TlsConfig],
    val extraMainConfig: Option[String],
    val extraVHostConfig: Option[String]
    ) extends LazyLogging {
  import scala.language.implicitConversions
  protected def pLog = ProcessLogger(logger.debug(_), logger.debug(_))

  val cacheDir = Files.createDirectory(baseDir.resolve("cache"))
  val configDir = Files.createDirectory(baseDir.resolve("conf"))
  val proxyDir = Files.createDirectory(baseDir.resolve("proxy"))
  val tmpDir = Files.createDirectory(baseDir.resolve("tmp"))
  val vhostDir = Files.createDirectory(configDir.resolve("vhost.d"))
  val mainConfig = configDir.resolve("nginx.conf")

  private val engine = Mustache.compiler().withCollector(ScalaCollector)

  lazy val mainConfigTemplater =
    engine.compile(readClasspathFile("nginx_main.tmpl.mustache"))
  lazy val vhostConfigTemplater =
    engine.compile(readClasspathFile("nginx_vhost.tmpl.mustache"))

  writeFile(mainConfig) {
    implicit def path2str(p: Path) = p.toAbsolutePath.toString
    mainConfigTemplater.execute(
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

  def replaceAllRoutes(routes: Seq[Route]) = {
    recursivelyDelete(vhostDir)
    Files.createDirectory(vhostDir)
    logger.info("Updating entire route set:")
    routes.foreach { route =>
      logger.info(route.domain)
      writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
    }
  }

  def setRoute(route: Route) = {
    logger.info(s"Setting route: ${route.domain}")
    writeFile(vhostDir.resolve(route.domain+".conf"))(vhostTmpl(route))
  }

  def deleteRoute(route: Route) = {
    logger.info(s"Deleting route: ${route.domain}")
    Files.delete(vhostDir.resolve(route.domain+".conf"))
  }

  def cleanup = recursivelyDelete(baseDir)

  protected def vhostTmpl(route: Route) =
    vhostConfigTemplater.execute(
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

  protected def readClasspathFile(path: String) =
    io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  protected def writeFile(f: Path)(content: String): Path =
    Files.write(f, content.getBytes("utf-8"))

  protected def recursivelyDelete(path: Path, retries: Int = 5) {
    if (Files.exists(path)) {
      try {
        Files.walkFileTree(path, new DeletingFileVisitor)
      } catch {
        case e: java.nio.file.NoSuchFileException =>
          if (retries > 0) {
            recursivelyDelete(path, retries - 1)
          }
      }
    }
  }

  implicit private def optionMapToMap[A,B](om: Option[Map[A,B]]): Map[A,B] =
    om.getOrElse(Map.empty[A,B])

  object ScalaCollector extends BasicCollector {
    override def toIterator(obj: Object) = super.toIterator(obj match {
      case v: Iterable[_] => asJavaIterable(v)
      case _ => obj
    })
    override def createFetcher(ctx: Object, name: String) =
      ctx match {
        case m: Map[_,_] => MapFetcher
        case seq: Seq[_] => SeqFetcher
        case _ => super.createFetcher(ctx, name)
      }
    override def createFetcherCache[K,V]() = new ConcurrentHashMap[K,V]

    object MapFetcher extends Mustache.VariableFetcher {
      override def get(ctx: Object, name: String) =
        ctx match {
          case m: Map[_,_] => optionToObject(Option(m.get(name)))
          case _ => Template.NO_FETCHER_FOUND
        }
    }

    object SeqFetcher extends Mustache.VariableFetcher {
      override def get(ctx: Object, name: String) =
        ctx match {
          case seq: Seq[_] => optionToObject(Try(seq(name.toInt)).toOption)
          case _ => Template.NO_FETCHER_FOUND
        }
    }

    def optionToObject(opt: Option[Any]) = opt match {
      case Some(obj: Any) => obj match {
        case m: Map[_,_] => mapAsJavaMap(m)
        case _ => obj.asInstanceOf[AnyRef]
      }
      case v => Template.NO_FETCHER_FOUND
    }

  }

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

object NginxConfig {

  def apply(
      baseDomain: Option[String],
      port: Int,
      tlsConfig: Option[TlsConfig],
      extraMainConfig: Option[String],
      extraVHostConfig: Option[String]) =
        new NginxConfig(Files.createTempDirectory("nginx-"),
            baseDomain, port, tlsConfig, extraMainConfig, extraVHostConfig)
}