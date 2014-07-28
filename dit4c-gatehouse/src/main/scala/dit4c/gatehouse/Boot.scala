package dit4c.gatehouse

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
    // start a new HTTP server on port 8080 with our service actor as handler
    IO(Http) ? Http.Bind(service, interface = "localhost", port = config.port)
  }

}

case class Config(
  val port: Int = 8080,
  val keyLocation: java.net.URI = null,
  val keyUpdateInterval: FiniteDuration = Duration.create(1, TimeUnit.HOURS))

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
  opt[Int]('p', "port")
    .action { (x, c) => c.copy(port = x) }
    .text("port to listen on")
  opt[java.net.URI]('s', "signed-by")
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