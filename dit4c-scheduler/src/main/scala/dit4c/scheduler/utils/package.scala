package dit4c.scheduler

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scopt.OptionParser

package object utils {

  trait AppMetadata {
    def name: String
    def version: String

    override def toString =
      s"${this.getClass.getSimpleName}" + (name, version)
  }

  trait ActorModule {
    def appName: String
    implicit val system = ActorSystem(appName)
    implicit val materializer = ActorMaterializer()

    sys.addShutdownHook(system.terminate())
  }

  trait ActorExecutionContext {
    def system: ActorSystem
    implicit val executionContext = system.dispatcher
  }

  case class SchedulerConfig(
      val name: String,
      val port: Int = 8080,
      val portalUri: String = "https://localhost:9000",
      val authImage: String = "https://github.com/dit4c/dit4c-helper-auth-portal/releases/download/0.0.4/dit4c-helper-auth-portal.linux.amd64.aci",
      val listenerImage: String = "https://github.com/dit4c/dit4c-helper-listener-ngrok2/releases/download/0.0.6/dit4c-helper-listener-ngrok2-au.linux.amd64.aci")

  class SchedulerConfigParser(app: AppMetadata)
      extends OptionParser[SchedulerConfig](app.name) {
    def parse(args: Seq[String]): Option[SchedulerConfig] =
      parse(args, SchedulerConfig(app.name))

    head(app.name, app.version)
    help("help").text("prints this usage text")
    version("version").abbr("V").text("show version")

    opt[Int]('p', "port")
      .action { (x, c) => c.copy(port = x) }
      .validate {
        case n if n >= 0 && n <= 0xFFFF => Right(())
        case n => Left("Invalid TCP port specified")
      }
      .text("port to listen on")

    opt[String]("portal-uri")
      .action { (x, c) => c.copy(portalUri = x) }
      .text("portal to connect to")

    opt[String]("auth-image")
      .action { (x, c) => c.copy(listenerImage = x) }
      .text("image to use for pod auth instead of the default")

    opt[String]("listener-image")
      .action { (x, c) => c.copy(listenerImage = x) }
      .text("image to use as pod listener")
  }

}