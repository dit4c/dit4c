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
import java.net.InetSocketAddress

class NginxConfig(
    val baseDir: Path,
    val port: Int,
    val tlsConfig: Option[TlsConfig],
    val extraMainConfig: Option[String],
    val authSocket: InetSocketAddress
    ) extends LazyLogging {
  import scala.language.implicitConversions
  protected def pLog = ProcessLogger(logger.debug(_), logger.debug(_))

  val cacheDir = Files.createDirectory(baseDir.resolve("cache"))
  val configDir = Files.createDirectory(baseDir.resolve("conf"))
  val proxyDir = Files.createDirectory(baseDir.resolve("proxy"))
  val tmpDir = Files.createDirectory(baseDir.resolve("tmp"))
  val mainConfig = configDir.resolve("nginx.conf")

  private val engine = Mustache.compiler().withCollector(ScalaCollector)

  lazy val configTemplater =
    engine.compile(readClasspathFile("nginx.tmpl.mustache"))

  writeFile(mainConfig) {
    implicit def path2str(p: Path) = p.toAbsolutePath.toString
    val authServer: String =
      s"http://${authSocket.getHostString}:${authSocket.getPort}"
    configTemplater.execute(
      Map[String,Any](
        "basedir" -> baseDir,
        "cachedir" -> cacheDir,
        "proxydir" -> proxyDir,
        "tmpdir" -> tmpDir,
        "authserver" -> authServer,
        "pidfile" -> baseDir.resolve("nginx.pid"),
        "port" -> port.toString
      ) ++ tlsConfig.map { c =>
          Map("tls" -> Map(
              "key" -> c.keyFile.getAbsolutePath,
              "certificate" -> c.certificateFile.getAbsolutePath))
        }
        ++ extraMainConfig.map { c =>
          Map("extraconf" -> c)
        }
    )
  }

  def cleanup = recursivelyDelete(baseDir)

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
      port: Int,
      tlsConfig: Option[TlsConfig],
      extraMainConfig: Option[String],
      authSocket: InetSocketAddress) =
        new NginxConfig(Files.createTempDirectory("nginx-"),
            port, tlsConfig, extraMainConfig, authSocket)
}