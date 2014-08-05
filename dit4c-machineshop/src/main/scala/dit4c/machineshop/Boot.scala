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
    val publicKeyLocation: Option[java.net.URI] = None,
    val keyUpdateInterval: FiniteDuration = Duration.create(1, TimeUnit.HOURS))

object ArgParser extends scopt.OptionParser[Config]("dit4c-machineshop") {
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
}