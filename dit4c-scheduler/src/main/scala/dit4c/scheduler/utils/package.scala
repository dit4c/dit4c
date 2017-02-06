package dit4c.scheduler

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scopt.OptionParser
import java.io.File
import dit4c.scheduler.domain.ClusterInfo
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

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
      val armoredPgpKeyring: Option[String],
      val knownClusters: Map[String, ClusterInfo] = Map(
          "default" -> ClusterInfo(
              "Default Cluster",
              true, true)),
      val port: Int = 8080,
      val portalUri: String = "ws://localhost:9000/messaging/scheduler/default",
      val authImage: String = "https://github.com/dit4c/dit4c-helper-auth-portal/releases/download/v0.1.2/dit4c-helper-auth-portal.linux.amd64.aci",
      val listenerImage: String = "https://github.com/dit4c/dit4c-helper-listener-ngrok2/releases/download/0.0.6/dit4c-helper-listener-ngrok2-au.linux.amd64.aci")

  class SchedulerConfigParser(app: AppMetadata)
      extends OptionParser[SchedulerConfig](app.name) {
    def parse(args: Seq[String]): Option[SchedulerConfig] =
      parse(args, SchedulerConfig(app.name, None))

    head(app.name, app.version)
    help("help").text("prints this usage text")
    version("version").abbr("V").text("show version")

    opt[File]('c', "config")
      .action { (f, c) =>
        import net.ceedubs.ficus.Ficus._
        val config: Config = ConfigFactory.parseFile(f)
        val clusterConfig: Map[String, Config] =
          config.as[Map[String, Config]]("clusters")
        val newClusters = clusterConfig.map { case (clusterId, config) =>
          val active = config.as[Option[Boolean]]("active").getOrElse(true)
          val displayName = config.as[Option[String]]("displayName").getOrElse(clusterId)
          val supportsSave = config.as[Option[Boolean]]("supportsSave").getOrElse(true)
          (clusterId -> ClusterInfo(displayName, active, supportsSave))
        }
        c.copy(knownClusters = c.knownClusters ++ newClusters)
      }
      .text("HOCON-format config file to get settings from")

    opt[File]('k', "keys")
      .required
      .action { (f, c) =>
        import scala.sys.process._
        c.copy(armoredPgpKeyring = Some(getFileContents(f)))
      }
      .validate {
        case f: File if f.exists() =>
          import dit4c.common.KeyHelpers.parseArmoredSecretKeyRing
          // Output error message from function, or else accept
          parseArmoredSecretKeyRing(getFileContents(f))
            .right.map(_ => ())
      }
      .text("OpenPGP secret keys used authentication")

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
      .action { (x, c) => c.copy(authImage = x) }
      .text("image to use for pod auth instead of the default")

    opt[String]("listener-image")
      .action { (x, c) => c.copy(listenerImage = x) }
      .text("image to use as pod listener")

    private def getFileContents(f: File) = {
      import scala.sys.process._
      f.cat.!!
    }
  }

}