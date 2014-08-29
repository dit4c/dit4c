package dit4c.machineshop

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import java.io.File
import java.util.concurrent.TimeUnit

object Boot extends App {

  override def main(args: Array[String]) {
    ArgParser.parse(args, Config()) map { config =>
      start(config)
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }

  def start(config: Config) {
    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("on-spray-can")

    val service = system.actorOf(
        Props(classOf[MainServiceActor], config),
        "main-service")

    implicit val timeout = Timeout(5.seconds)
    // start a new HTTP server on specified interface and port
    IO(Http) ? Http.Bind(service,
        interface = config.interface,
        port = config.port)
  }

}

case class Config(
    val interface: String = "localhost",
    val port: Int = 8080,
    val serverId: String = null,
    val publicKeyLocation: Option[java.net.URI] = None,
    val keyUpdateInterval: FiniteDuration = Duration.create(1, TimeUnit.HOURS))

object ArgParser extends scopt.OptionParser[Config]("dit4c-machineshop") {
  // Courtesy of https://gist.github.com/mayoYamasaki/4085712
  def sha1(bytes: Array[Byte]) = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    md.digest(bytes).map("%02x".format(_)).mkString
  }

  help("help") text("prints this usage text")
  opt[String]('i', "interface")
    .action { (x, c) => c.copy(interface = x) }
    .text("interface to bind to")
  opt[Int]('p', "port")
    .action { (x, c) => c.copy(port = x) }
    .text("port to listen on")
  opt[java.net.URI]('s', "signed-by")
    .action { (x, c) => c.copy(publicKeyLocation = Some(x)) }
    .text("URL/file of JWK RSA keyset used to sign privileged requests")
  opt[Int]('k', "key-refresh")
    .optional()
    .action { (x, c) =>
      c.copy(keyUpdateInterval = Duration.create(x, TimeUnit.SECONDS))
     }
    .text("second interval to use when polling keys")
  opt[String]("server-id-seed")
    .action { (x, c) => c.copy(serverId = sha1(x.getBytes)) }
    .text("seed string for server ID")
  opt[java.io.File]("server-id-seed-file")
    .action { (file, c) =>
      def readFileBytes(file: java.io.File) = {
        import java.io._
        val bis = new BufferedInputStream(new FileInputStream(file))
        Stream.continually(bis.read).takeWhile(-1 != _).map(_.toByte).toArray
      }
      c.copy(serverId = sha1(readFileBytes(file)))
    }
    .validate { file =>
      if (file.isFile) Right() else Left("server ID file must exist")
    }
    .text("file containing seed data for server ID")
  checkConfig { c =>
    if (c.serverId == null) failure("server ID must be set")
    else success
  }

}