package dit4c.gatehouse

import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import dit4c.gatehouse.docker._
import dit4c.gatehouse.auth.AuthActor
import scala.util.{Success,Failure}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

object Boot extends App {
  implicit val system = ActorSystem("dit4c-gatehouse")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(10, TimeUnit.SECONDS)
  val log = Logging.getLogger(system, this)
  import system.dispatcher

  def start(config: Config) {
    val route = {
      val dockerClient = new DockerClient(config.dockerHost)
      val dockerIndex = system.actorOf(
          Props(classOf[DockerIndexActor], dockerClient), "docker-index")
      val auth = system.actorOf(
          Props(classOf[AuthActor], config.keyLocation, config.keyUpdateInterval),
          "auth")
      AuthService(system, dockerIndex, auth).route.~(MiscService.route)
    }
    Http().bindAndHandle(route, config.interface, config.port) onComplete {
      case Success(b) =>
        log.info(s"Successfully bound to ${b.localAddress}")
      case Failure(msg) =>
        system.shutdown()
        log.error(msg.getMessage)
        System.exit(1)
    }
  }

  ArgParser.parse(args, Config()) map { config =>
    start(config)
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }

}

case class Config(
  val interface: String = "localhost",
  val port: Int = 8080,
  val dockerHost: URI = URI.create("http://127.0.0.1:2375/"),
  val keyLocation: URI = null,
  val keyUpdateInterval: FiniteDuration = Duration.create(1, TimeUnit.HOURS))

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
  opt[String]('i', "interface")
    .action { (x, c) => c.copy(interface = x) }
    .text("interface to bind to")
  opt[Int]('p', "port")
    .action { (x, c) => c.copy(port = x) }
    .text("port to listen on")
  opt[URI]('H', "docker-host")
    .action { (x, c) => c.copy(dockerHost = x) }
    .text("Docker URI")
  opt[URI]('s', "signed-by")
    .required()
    .action { (x, c) => c.copy(keyLocation = x) }
    .text("URL/file of JWK RSA keyset used to sign JWT tokens")
  opt[Int]('k', "key-refresh")
    .optional()
    .action { (x, c) =>
      c.copy(keyUpdateInterval = Duration.create(x, TimeUnit.SECONDS))
     }
    .text("second interval to use when polling keys")

}
