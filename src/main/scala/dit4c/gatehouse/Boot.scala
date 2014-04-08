package dit4c.gatehouse

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

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

    val service = system.actorOf(Props[MainServiceActor], "main-service")

    implicit val timeout = Timeout(5.seconds)
    // start a new HTTP server on port 8080 with our service actor as handler
    IO(Http) ? Http.Bind(service, interface = "localhost", port = 8080)
  }

}

case class Config()

object ArgParser extends scopt.OptionParser[Config]("dit4c-gatehouse") {
  help("help") text("prints this usage text")
}