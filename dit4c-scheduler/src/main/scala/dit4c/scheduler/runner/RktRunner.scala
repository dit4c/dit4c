package dit4c.scheduler.runner

import java.io.ByteArrayInputStream
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import play.api.libs.json._
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.KeyPairGenerator
import scala.util.Random
import pdi.jwt.Jwt
import pdi.jwt.algorithms.JwtAsymetricAlgorithm
import pdi.jwt.JwtAlgorithm
import akka.http.scaladsl.model.Uri
import dit4c.scheduler.utils.KeyHelpers._
import java.nio.file.Paths

object RktRunner {
  case class Config(
      rktDir: Path,
      instanceNamePrefix: String,
      listenerImage: String)
}

trait RktRunner {
  type ImageId = String

  def fetch(imageName: String): Future[ImageId]
  def start(
      instanceId: String,
      image: ImageId,
      portalUri: String): Future[RSAPublicKey]
  def stop(instanceId: String): Future[Unit]

}

class RktRunnerImpl(
    val ce: CommandExecutor,
    val config: RktRunner.Config)(
        implicit ec: ExecutionContext) extends RktRunner {

  if (!config.instanceNamePrefix.matches("""[a-z0-9\-]+"""))
    throw new IllegalArgumentException(
        "Only lower-case alphanumerics & '-' allowed for instance prefix")

  def fetch(imageName: String): Future[ImageId] =
    privileged(rktCmd)
      .flatMap { rktCmd =>
        ce(rktCmd :+ "fetch" :+
            "--insecure-options=image" :+ "--full" :+
            imageName)
      }
      .map(_.trim)

  def guessServicePort(image: ImageId): Future[Int] = {
    privileged(rktCmd)
      .flatMap { rktCmd => ce(rktCmd :+ "image" :+ "cat-manifest" :+ image) }
      .map(Json.parse)
      .map { json =>
        // Extract all registered TCP ports
        val possiblePorts: Seq[Int] =
          (json \ "app" \ "ports").as[Seq[JsObject]]
            .filter(v => (v \ "protocol").as[String] == "tcp")
            .map(v => (v \ "port").as[Int])
        // Pick the lowest
        possiblePorts.min
      }
  }

  def start(
      instanceId: String,
      image: ImageId,
      portalUri: String): Future[RSAPublicKey] =
    if (instanceId.matches("""[a-z0-9\-]+""")) {
      for {
        (manifestFile, publicKey) <-
          generateManifestFile(instanceId, image, portalUri)
        systemdRun <- privileged(systemdRunCmd)
        rkt <- rktCmd
        output <- ce(
            systemdRun ++
            Seq(s"--unit=${config.instanceNamePrefix}-${instanceId}.service") ++
            rkt ++
            Seq("run", "--net=default", "--dns=8.8.8.8", "--no-overlay") ++
            Seq(s"--pod-manifest=$manifestFile")
        )
      } yield publicKey
    } else throw new IllegalArgumentException(
      "Only lower-case alphanumerics & '-' allowed in instance IDs")

  def stop(instanceId: String): Future[Unit] =
    privileged(systemctlCmd)
      .flatMap { systemctl =>
        ce(
          systemctl :+ "stop" :+
          s"${config.instanceNamePrefix}-${instanceId}.service")
      }
      .map { _ => () }

  protected[runner] def listSystemdUnits: Future[Set[SystemdUnit]] =
    systemctlCmd
      .flatMap { bin => ce(bin :+ "list-units" :+ "--no-legend" :+ s"${config.instanceNamePrefix}*") }
      .map(_.trim)
      .map { output =>
        output.lines.map { line =>
          var parts = line.split("""\s+""").toList
          val (name :: _) = parts
          SystemdUnit(name.stripSuffix(".service"))
        }.toSet
      }

  /**
   * List rkt pods. Runs as root.
   */
  protected[runner] def listRktPods: Future[Set[RktPod]] =
    privileged(rktCmd)
      .flatMap { rktCmd => ce(rktCmd :+ "list" :+ "--full" :+ "--no-legend") }
      .map(_.trim)
      .map { output =>
        output.lines.foldLeft(List.empty[RktPod]) { (m, l) =>
          l match {
            case line if line.matches("""\s.*""") =>
              val appName =
                line.dropWhile(_.isWhitespace).takeWhile(!_.isWhitespace)
              m.init :+ m.last.copy(apps = m.last.apps + appName)
            case line =>
              var parts = line.split("""(\t+|\s\s+)""").toList
              val (uuid :: appName :: _ :: _ :: state :: _) = parts
              m :+ RktPod(uuid, Set(appName), RktPod.States.fromString(state))
          }
        }.toSet
      }

  private def rktCmd = which("rkt").map(_ :+ s"--dir=${config.rktDir}")

  private def systemdRunCmd = which("systemd-run")

  private def systemctlCmd = which("systemctl")

  private def privileged(cmd: Future[Seq[String]]): Future[Seq[String]] =
    cmd.map(Seq("sudo", "-n", "--") ++ _)

  protected[runner] def which(cmd: String): Future[Seq[String]] =
    ce(Seq("which", cmd))
      .map(_.trim)
      .map {
        case s if s.isEmpty => throw new Exception(s"`which $cmd` was blank")
        case s => Seq(s)
      }

  private def generateManifestFile(
      instanceId: String,
      image: ImageId,
      portalUri: String): Future[(String, RSAPublicKey)] = {
    for {
      socatImageId <- fetch("quay.io/ridero/socat")
      listenerImageId <- fetch(config.listenerImage)
      servicePort <- guessServicePort(image)
      (privateKey, publicKey) = newKeyPair
      instanceKeyInternalPath = "/dit4c/pki/instance-key.pem"
      vfm <- tempVolumeFileManager(s"instance-$instanceId")
      configMountJson = Json.obj(
          "volume" -> "dit4c-instance-config",
          "path" -> "/dit4c")
      configVolumeJson = Json.obj(
          "name" -> "dit4c-instance-config",
          "kind" -> "host",
          "readOnly" -> true,
          "source" -> vfm.baseDir)
      helperEnvVars = Map[String, String](
          "DIT4C_INSTANCE_PRIVATE_KEY" -> instanceKeyInternalPath,
          "DIT4C_INSTANCE_JWT_ISS" -> s"instance-$instanceId",
          "DIT4C_INSTANCE_JWT_KID" -> privateKey.pkcs1.der.digest("SHA-256").base64,
          "DIT4C_INSTANCE_HELPER_AUTH_HOST" -> "127.68.73.84",
          "DIT4C_INSTANCE_HELPER_AUTH_PORT" -> "5267",
          "DIT4C_INSTANCE_HTTP_PORT" -> servicePort.toString,
          "DIT4C_INSTANCE_URI_UPDATE_URL" -> Uri(portalUri).withPath(Uri.Path("/instances/")).toString)
      _ <- vfm.writeFile("env.sh",
          (helperEnvVars.map({case (k, v) => s"$k=$v"}).toSeq.sorted.mkString("\n")+"\n").getBytes)
      _ <- vfm.writeFile("pki/instance-key.pem", privateKey.pkcs1.pem.getBytes)
      manifest = Json.obj(
        "acVersion" -> "0.8.4",
        "acKind" -> "PodManifest",
        "apps" -> Json.arr(
          Json.obj(
            "name" -> s"${config.instanceNamePrefix}-${instanceId}",
            "image" -> Json.obj(
              "id" -> image)),
          Json.obj(
            "name" -> "helper-listener",
            "image" -> Json.obj(
              "id" -> listenerImageId),
            "mounts" -> Json.arr(configMountJson)),
          Json.obj(
            "name" -> "helper-auth",
            "image" -> Json.obj(
              "id" -> socatImageId),
            "app" -> Json.obj(
              "exec" -> Json.arr(
                "sh", "-c",
                "set -aex && source /dit4c/env.sh && socat -d TCP-LISTEN:$DIT4C_INSTANCE_HELPER_AUTH_PORT,bind=$DIT4C_INSTANCE_HELPER_AUTH_HOST,fork,reuseaddr TCP:127.0.0.1:$DIT4C_INSTANCE_HTTP_PORT"),
              "group" -> "99",
              "user" -> "99"),
            "mounts" -> Json.arr(configMountJson),
            "readOnlyRootFS" -> true)),
        "volumes" -> Json.arr(configVolumeJson))
      output <- tempVolumeFileManager(s"manifest-$instanceId").flatMap(vfm =>
        vfm.writeFile("manifest.json", (Json.prettyPrint(manifest)+"\n").getBytes))
      filepath = output.trim
    } yield (filepath, publicKey)
  }

  private def tempVolumeFileManager(dirPrefix: String): Future[VolumeFileManager] =
    ce(Seq("sh", "-c", Seq(
            s"DIR=$$(mktemp -d --tmpdir $dirPrefix-XXXX)",
            "chmod o=rx $DIR",
            "echo $DIR").mkString(" && "))).map(s => new VolumeFileManager(s.trim))

  private class VolumeFileManager(val baseDir: String) {
    def writeFile(filename: String, content: Array[Byte]): Future[String] = {
      val f = Paths.get(baseDir).resolve(filename.stripPrefix("/"))
      ce(Seq("sh", "-c", Seq(
            s"mkdir -p $$(dirname $f)",
            s"cat > $f",
            s"test -f $f",
            s"echo $f").mkString(" && ")), new ByteArrayInputStream(content))
    }
  }

  private def newKeyPair: (RSAPrivateKey, RSAPublicKey) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair
    (
      kp.getPrivate.asInstanceOf[RSAPrivateKey],
      kp.getPublic.asInstanceOf[RSAPublicKey]
    )
  }

  private def hostIp: Future[String] =
    ce(Seq(
        "sh", "-c",
        "ip route get 8.8.8.8"))
      .collect {
        case s if s.startsWith("8.8.8.8") =>
          s.lines.next.split(" ").last
      }

  private def jwtToken(id: String, key: RSAPrivateKey): String = {
    Jwt.encode(s"""{"iss":"instance/$id"}""", key, JwtAlgorithm.RSASHA512)
  }

  private def rktEnv(pairs: (String, String)*): JsArray = JsArray(
    pairs.map { case (k: String, v: String) => Json.obj("name" -> k, "value" -> v) })


}