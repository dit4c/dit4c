package dit4c.machineshop

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging
import akka.io.IO
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import scala.concurrent.duration._
import java.io.File
import java.util.concurrent.TimeUnit
import scalax.file.{FileSystem,Path}
import scala.util.{Success,Failure}
import dit4c.machineshop.docker.DockerClientImpl
import dit4c.machineshop.docker.models.ContainerLink
import dit4c.machineshop.images.{ImageManagementActor, KnownImages}
import dit4c.machineshop.auth.SignatureActor

object Boot extends App {
  implicit val system = ActorSystem("dit4c-machineshop")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(10, TimeUnit.SECONDS)
  val log = Logging.getLogger(system, this)
  import system.dispatcher

  def start(config: Config) {
    val route = {
      val knownImages = new KnownImages(config.knownImageFile)
      val dockerClient = new DockerClientImpl(
          Uri("http://127.0.0.1:2375/"), config.containerLinks)
      val signatureActor: Option[ActorRef] =
        config.publicKeyLocation.map { loc =>
          system.actorOf(
            Props(classOf[SignatureActor], loc, config.keyUpdateInterval),
            "signature-checker")
        }
      val imageMonitor = system.actorOf(
          Props(classOf[ImageManagementActor],
            knownImages, dockerClient, config.imageUpdateInterval),
          "image-monitor")

      MiscService(config.serverId).route ~
        ApiService(dockerClient, imageMonitor, signatureActor).route
    }
    val bindingFuture =
      Http().bindAndHandle(route, config.interface, config.port)
    bindingFuture.onComplete {
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
    val serverId: String = null,
    val publicKeyLocation: Option[java.net.URI] = None,
    val keyUpdateInterval: FiniteDuration = Duration.create(1, TimeUnit.HOURS),
    val imageUpdateInterval: Option[FiniteDuration] = None,
    val containerLinks: Seq[ContainerLink] = Nil,
    val knownImageFile: scalax.file.Path =
      FileSystem.default.fromString("known_images.json"))

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
    .text("second interval to use when polling keys")
  opt[String]("server-id-seed")
    .action { (x, c) => c.copy(serverId = sha1(x.getBytes)) }
    .text("seed string for server ID")
  opt[java.io.File]("server-id-seed-file")
    .action { (file, c) =>
      def readFileBytes(file: java.io.File) = {
        FileSystem.default
          .fromString(file.getAbsolutePath)
          .inputStream.byteArray
      }
      c.copy(serverId = sha1(readFileBytes(file)))
    }
    .validate { file =>
      if (file.isFile) Right(()) else Left("server ID file must exist")
    }
    .text("file containing seed data for server ID")
  opt[java.io.File]("known-images-file")
    .action { (file, c) =>
      c.copy(knownImageFile =
        FileSystem.default.fromString(file.getAbsolutePath))
    }
    .text("file to track known images in")
  opt[Int]("image-update-interval")
    .action { (x, c) =>
      c.copy(imageUpdateInterval = Some(Duration.create(x, "seconds")))
    }
    .text("pull image updates every <n> seconds")
  opt[String]("link")
    .action { (x, c) =>
      c.copy(containerLinks = c.containerLinks :+ ContainerLink(x))
    }
    .text("container link to add to all new containers")
  checkConfig { c =>
    if (c.serverId == null) failure("server ID must be set")
    else success
  }

}
