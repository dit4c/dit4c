package dit4c.switchboard

import java.io._
import java.net.URI
import akka.actor._
import akka.pattern.after
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.io.Framing
import akka.util.ByteString
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util._
import com.typesafe.scalalogging.LazyLogging
import org.bouncycastle.openssl.PEMParser
import dit4c.common.AkkaHttpExtras._
import akka.event.Logging
import akka.http.ClientConnectionSettings
import dit4c.switchboard.nginx._

object Boot extends App with LazyLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  lazy val log = Logging(system, this.getClass.getName)

  import Route._

  def monitorFeed(config: Config, nginx: NginxInstance, retryWait: FiniteDuration = 5.seconds): Unit =
    Http().singleResilientRequest(
        HttpRequest(uri = config.feed.toString),
        ClientConnectionSettings(system), None, log)
      .map { response =>
        response.entity match {
          case HttpEntity.Chunked(mimeType, parts) if mimeType.mediaType.value == "text/event-stream" =>
            parts
              .takeWithin(1.hour) // Avoid timeouts
              .map(_.data)
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue))
              .map(v => new String(v.decodeString(mimeType.charset.value)))
              .filter(_.startsWith("data: "))
              .map(_.replaceFirst("data: ", ""))
              .map(Json.parse(_))
          case entity =>
            throw new Exception(
                "Feed should be a chunked EventSource stream: "+response)
        }
      }
      .flatMap { source =>
        source.runForeach { v =>
          (v \ "op").as[String] match {
            case "replace-all-routes" =>
              nginx.replaceAllRoutes((v \ "routes").as[Seq[Route]])
            case "set-route" =>
              nginx.setRoute((v \ "route").as[Route])
            case "delete-route" =>
              nginx.deleteRoute((v \ "route").as[Route])
          }
        }
      }
      .onComplete {
        case Success(()) =>
          logger.warn("Disconnected from feed. Reconnecting...")
          monitorFeed(config, nginx)
        case Failure(e) =>
          logger.error(s"Feed connection error: $e")
          logger.error(s"Waiting $retryWait before retry...")
          after(retryWait, system.scheduler)(Future.successful(()))
            .onComplete { _ => monitorFeed(config, nginx) }
      }

  ArgParser.parse(args, Config()) map { config =>
    val tlsConfig =
      for { k <- config.sslKey; c <- config.sslCertificate } yield TlsConfig(c,k)
    tlsConfig match {
      case Some(c) =>
        logger.info(s"Starting as HTTPS server: ${c.certificate.getSubject}")
      case None =>
        logger.info("Starting as HTTP server")
    }
    val nginx = new NginxInstance(config.baseDomain, config.port, tlsConfig,
        config.extraMainConfig, config.extraVHostConfig)
    monitorFeed(config, nginx)
  } getOrElse {
    // arguments are bad, error message will have been displayed
    System.exit(1)
  }

}

case class Config(
  val feed: URI = new URI("https://example.test/routes"),
  val baseDomain: Option[String] = None,
  val port: Int = 9200,
  val sslCertificate: Option[File] = None,
  val sslKey: Option[File] = None,
  val extraMainConfig: Option[String] = None,
  val extraVHostConfig: Option[String] = None)

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
  opt[URI]('f', "feed")
    .action { (x, c) => c.copy(feed = x) }
    .text("DIT4C Highcommand route feed")
  opt[String]('d', "domain")
    .action { (x, c) => c.copy(baseDomain = Some(x)) }
    .text("DIT4C base domain (ie. where Highcommand is hosted)")
  opt[Int]('p', "port")
    .action { (x, c) => c.copy(port = x) }
    .text("port for Nginx to listen on")
  opt[File]('c', "ssl-certificate")
    .action { (x, c) => c.copy(sslCertificate = Some(x)) }
    .text("SSL certificate for HTTPS")
  opt[File]('k', "ssl-key")
    .action { (x, c) => c.copy(sslKey = Some(x)) }
    .text("SSL certificate for HTTPS")
  opt[File]("extra-main-config")
    .action { (f, c) =>
      val config = scala.io.Source.fromFile(f).mkString
      c.copy(extraMainConfig = Some(config))
    }
    .text("Include extra main (`http {...}`) config")
  opt[File]("extra-vhost-config")
    .action { (f, c) =>
      val config = scala.io.Source.fromFile(f).mkString
      c.copy(extraVHostConfig = Some(config))
    }
    .text("Include extra vhost (`server {...}`) config")
}
