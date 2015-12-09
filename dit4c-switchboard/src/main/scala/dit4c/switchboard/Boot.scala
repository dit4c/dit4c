package dit4c.switchboard

import java.io._
import java.net.URI
import akka.actor._
import akka.agent._
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
import dit4c.switchboard.http.AuthRequestServer

object Boot extends App with LazyLogging {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  ArgParser.parse(args, Config()) map { config =>
    val tlsConfig =
      for { k <- config.sslKey; c <- config.sslCertificate } yield TlsConfig(c,k)
    tlsConfig match {
      case Some(c) =>
        logger.info(s"Starting as HTTPS server: ${c.certificate.getSubject}")
      case None =>
        logger.info("Starting as HTTP server")
    }
    val routes: Agent[Option[Map[String,Route]]] = Agent(None)
    def routeResolver = routes.get.map(v => v.get(_))
    val nginx = AuthRequestServer.start(routeResolver _).map { instance =>
      sys.addShutdownHook(instance.shutdown _)
      logger.info(s"Auth Server listening on: ${instance.socket}")
      new NginxInstance(config.port, tlsConfig,
          config.extraMainConfig, instance.socket)
    }
    FeedMonitor(config) { v =>
      (v \ "op").as[String] match {
        case "replace-all-routes" =>
          val newRoutes = (v \ "routes").as[Seq[Route]]
          routes.alter(Some(newRoutes.map(v => (v.domain,v)).toMap))
            .foreach(_ => logger.info(s"Populated with ${newRoutes.size} routes"))
        case "set-route" =>
          val newRoute = (v \ "route").as[Route]
          routes.alter { (routeMap: Option[Map[String,Route]]) =>
            routeMap.map(_ + (newRoute.domain -> newRoute))
          }.foreach(_ => logger.info(s"Added new route: $newRoute"))
        case "delete-route" =>
          val deletedRoute = (v \ "route").as[Route]
          routes.alter { (routeMap: Option[Map[String,Route]]) =>
            routeMap.map(_ - deletedRoute.domain)
          }.foreach(_ => logger.info(s"Deleted route: $deletedRoute"))
      }
    }
  } getOrElse {
    // arguments are bad, error message will have been displayed
    System.exit(1)
  }

}

case class Config(
  val feed: URI = new URI("https://example.test/routes"),
  val port: Int = 9200,
  val sslCertificate: Option[File] = None,
  val sslKey: Option[File] = None,
  val extraMainConfig: Option[String] = None)

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
  opt[URI]('f', "feed")
    .action { (x, c) => c.copy(feed = x) }
    .text("DIT4C Highcommand route feed")
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
}
