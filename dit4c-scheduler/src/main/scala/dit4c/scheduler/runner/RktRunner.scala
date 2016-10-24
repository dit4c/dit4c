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
      authImage: String,
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
  def export(instanceId: String): Future[Unit]
  def uploadImage(instanceId: String,
      helperImage: String,
      imageServer: String,
      portalUri: String): Future[Unit]

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
            "--no-store" :+ // See https://coreos.com/rkt/docs/latest/image-fetching-behavior.html
            "--insecure-options=image" :+ "--full" :+
            imageName)
      }
      .map(_.trim)

  def guessServicePort(image: ImageId): Future[Int] =
    getImageManifest(image)
      .map { json =>
        // Extract all registered TCP ports
        val possiblePorts: Seq[Int] =
          (json \ "app" \ "ports").as[Seq[JsObject]]
            .filter(v => (v \ "protocol").as[String] == "tcp")
            .map(v => (v \ "port").as[Int])
        // Pick the lowest
        possiblePorts.min
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
            Seq(s"--unit=${podAppName(instanceId)}.service") ++
            rkt ++
            Seq("run", "--net=default", "--dns=8.8.8.8") ++
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
          s"${podAppName(instanceId)}.service")
      }
      .map { _ => () }

  def export(instanceId: String): Future[Unit] =
    listRktPods
      .map { pods =>
        pods.find { p =>
          p.apps.contains(podAppName(instanceId)) &&
          p.state == RktPod.States.Exited
        }
      }
      .collect {
        case Some(pod) => pod
      }
      .flatMap { pod =>
        for {
          vfm <- imageVolumeFileManager(instanceId)
          aciTmpName = Random.alphanumeric.take(40).mkString + ".aci"
          aciName = podAppName(instanceId) + ".aci"
          aciTmpPath <- vfm.absolutePath(aciTmpName)
          done <-
            privileged(rktCmd)
              .flatMap { rktCmd =>
                ce(rktCmd :+ "export" :+ "--app" :+ podAppName(instanceId) :+ pod.uuid :+ aciTmpPath)
              }
              .flatMap { _ => vfm.moveFile(aciTmpName, aciName) }
        } yield done
      }


  def uploadImage(
      instanceId: String,
      helperImage: String,
      imageServer: String,
      portalUri: String): Future[Unit] =
    for {
      helperImageId <- fetch(helperImage)
      instanceKeysInternalPath = "/dit4c/instance/pki"
      helperEnvVars = Map[String,String](
        "DIT4C_IMAGE" -> s"/dit4c/image/${podAppName(instanceId)}.aci",
        "DIT4C_IMAGE_ID" -> instanceId,
        "DIT4C_IMAGE_SERVER" -> imageServer,
        "DIT4C_IMAGE_UPLOAD_NOTIFICATION_URL" -> Uri(portalUri).withPath(Uri.Path("/images/")).toString,
        "DIT4C_INSTANCE_PRIVATE_KEY_PKCS1" -> s"${instanceKeysInternalPath}/instance-key.pem",
        "DIT4C_INSTANCE_PRIVATE_KEY_OPENPGP" -> s"${instanceKeysInternalPath}/instance-key.openpgp.asc",
        "DIT4C_INSTANCE_PRIVATE_KEY_OPENPGP_PASSPHRASE" -> instanceId,
        "DIT4C_INSTANCE_JWT_ISS" -> s"instance-$instanceId",
        "DIT4C_INSTANCE_JWT_KID" -> instanceId
      )
      helperAppConfigJson <- getImageAppConfig(helperImageId).map(addExtraEnvVars(_, helperEnvVars))
      instanceConfigDir <- imageVolumeFileManager(instanceId).map(_.baseDir)
      imageDir <- imageVolumeFileManager(instanceId).map(_.baseDir)
      manifest = Json.obj(
        "acVersion" -> "0.8.4",
        "acKind" -> "PodManifest",
        "apps" -> Json.arr(
          Json.obj(
            "name" -> s"upload-$instanceId",
            "image" -> Json.obj("id" -> helperImageId),
            "app" -> helperAppConfigJson,
            "mounts" -> Json.arr(
              Json.obj(
                "volume" -> "dit4c-instance-config",
                "path" -> "/dit4c/instance"),
              Json.obj(
                "volume" -> "dit4c-image-dir",
                "path" -> "/dit4c/image")
            )
          )
        ),
        "volumes" -> Json.arr(
          Json.obj(
            "name" -> "dit4c-instance-config",
            "kind" -> "host",
            "readOnly" -> true,
            "source" -> instanceConfigDir),
          Json.obj(
            "name" -> "dit4c-image-dir",
            "kind" -> "host",
            "readOnly" -> true,
            "source" -> imageDir))
      )
      systemdRun <- privileged(systemdRunCmd)
      rkt <- rktCmd
      manifestFile <- tempVolumeFileManager(s"manifest-upload-$instanceId").flatMap(vfm =>
        vfm.writeFile("manifest.json", (Json.prettyPrint(manifest)+"\n").getBytes))
      output <- ce(
          systemdRun ++
          Seq(s"--unit=upload-${instanceId}.service") ++
          rkt ++
          Seq("run", "--net=default", "--dns=8.8.8.8") ++
          Seq(s"--pod-manifest=$manifestFile")
      )
    } yield ()

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

  protected[runner] def getImageManifest(image: ImageId): Future[JsObject] =
    privileged(rktCmd)
      .flatMap { rktCmd => ce(rktCmd :+ "image" :+ "cat-manifest" :+ image) }
      .map(Json.parse)
      .map(_.as[JsObject])

  private def rktCmd = which("rkt").map(_ :+ s"--dir=${config.rktDir}")

  private def systemdRunCmd = which("systemd-run")

  private def systemctlCmd = which("systemctl")

  private def privileged(cmd: Seq[String]): Seq[String] = Seq("sudo", "-n", "--") ++ cmd

  private def privileged(cmd: Future[Seq[String]]): Future[Seq[String]] = cmd.map(privileged)

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
      authImageId <- fetch(config.authImage)
      listenerImageId <- fetch(config.listenerImage)
      servicePort <- guessServicePort(image)
      (privateKey, publicKey) = newKeyPair
      instanceKeyInternalPath = "/dit4c/pki/instance-key.pem"
      vfm <- instanceVolumeFileManager(instanceId)
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
          "DIT4C_INSTANCE_JWT_KID" -> instanceId,
          "DIT4C_INSTANCE_HELPER_AUTH_HOST" -> "127.68.73.84",
          "DIT4C_INSTANCE_HELPER_AUTH_PORT" -> "5267",
          "DIT4C_INSTANCE_HTTP_PORT" -> servicePort.toString,
          "DIT4C_INSTANCE_URI_UPDATE_URL" -> Uri(portalUri).withPath(Uri.Path("/instances/")).toString,
          "DIT4C_INSTANCE_OAUTH_AUTHORIZE_URL" -> Uri(portalUri).withPath(Uri.Path("/login/oauth/authorize")).toString,
          "DIT4C_INSTANCE_OAUTH_ACCESS_TOKEN_URL" -> Uri(portalUri).withPath(Uri.Path("/login/oauth/access_token")).toString
      )
      authImageAppJson <- getImageAppConfig(authImageId).map(addExtraEnvVars(_, helperEnvVars))
      listenerImageAppJson <- getImageAppConfig(listenerImageId).map(addExtraEnvVars(_, helperEnvVars))
      _ <- vfm.writeFile("pki/instance-key.pem", privateKey.pkcs1.pem.getBytes)
      // Export key with passphrase, otherwise GnuPG can refuse to read it
      _ <- vfm.writeFile("pki/instance-key.openpgp.asc",
          (privateKey, publicKey).openpgp(s"DIT4C Instance $instanceId", Some(instanceId)).`private`.armoured)
      manifest = Json.obj(
        "acVersion" -> "0.8.4",
        "acKind" -> "PodManifest",
        "apps" -> Json.arr(
          Json.obj(
            "name" -> podAppName(instanceId),
            "image" -> Json.obj(
              "id" -> image)),
          Json.obj(
            "name" -> "helper-listener",
            "image" -> Json.obj(
              "id" -> listenerImageId),
            "app" -> listenerImageAppJson,
            "mounts" -> Json.arr(configMountJson)),
          Json.obj(
            "name" -> "helper-auth",
            "image" -> Json.obj(
              "id" -> authImageId),
            "app" -> authImageAppJson,
            "mounts" -> Json.arr(configMountJson))
        ),
        "volumes" -> Json.arr(configVolumeJson))
      filepath <- tempVolumeFileManager(s"manifest-$instanceId").flatMap(vfm =>
        vfm.writeFile("manifest.json", (Json.prettyPrint(manifest)+"\n").getBytes))
    } yield (filepath, publicKey)
  }

  private def tempVolumeFileManager(dirPrefix: String): Future[VolumeFileManager] =
    ce(privileged(Seq("sh", "-c", Seq(
            s"DIR=$$(mktemp -d --tmpdir $dirPrefix-XXXX)",
            "chmod o=rx $DIR",
            "echo $DIR").mkString(" && ")))).map(s => new VolumeFileManager(s.trim))

  private def instanceVolumeFileManager(instanceId: String): Future[VolumeFileManager] = {
    val dir = s"${config.rktDir}/dit4c-volumes/instances/$instanceId"
    ce(privileged(Seq("sh", "-c", Seq(
            s"mkdir -p $dir",
            s"chmod o=rx $dir",
            s"echo $dir").mkString(" && ")))).map(s => new VolumeFileManager(s.trim))
  }

  private def imageVolumeFileManager(instanceId: String): Future[VolumeFileManager] = {
    val dir = s"${config.rktDir}/dit4c-volumes/images/$instanceId"
    ce(privileged(Seq("sh", "-c", Seq(
            s"mkdir -p $dir",
            s"chmod o=rx $dir",
            s"echo $dir").mkString(" && ")))).map(s => new VolumeFileManager(s.trim))
  }

  private class VolumeFileManager(val baseDir: String) {
    def writeFile(filename: String, content: Array[Byte]): Future[String] = {
      val f = resolve(filename)
      ce(privileged(Seq("sh", "-c", Seq(
            s"mkdir -p $$(dirname $f)",
            s"cat > $f",
            s"test -f $f",
            s"echo $f").mkString(" && "))), new ByteArrayInputStream(content)).map(_.trim)
    }

    def absolutePath(filename: String): Future[String] =
      ce(privileged(Seq("readlink", "-f", resolve(filename)))).map(_.trim)

    def moveFile(from: String, to: String): Future[Unit] = {
      ce(privileged(Seq("sh", "-c", Seq(
            s"mkdir -p $$(dirname ${resolve(from)})",
            s"mkdir -p $$(dirname ${resolve(to)})",
            s"mv ${resolve(from)} ${resolve(to)}").mkString(" && ")))).map(_ => ())
    }

    protected def resolve(filename: String): String =
      Paths.get(baseDir).resolve(filename.stripPrefix("/")).toAbsolutePath.toString
  }

  private def addExtraEnvVars(appConfig: JsObject, extraEnvVars: Map[String, String]): JsObject = {
    // Helper functions
    def jsObj2Tuple(obj: JsObject): Option[(String, String)] =
      for {
        name <- (obj \ "name").asOpt[String]
        value <- (obj \ "value").asOpt[String]
      } yield (name, value)
    def tuple2jsObj(p: (String, String)): JsObject =
      Json.obj("name" -> p._1, "value" -> p._2)
    // Replacement
    val newEnvVars: Map[String, String] = (appConfig \ "environment").asOpt[Seq[JsObject]]
      .map(_.flatMap(jsObj2Tuple).toMap ++ extraEnvVars)
      .getOrElse(extraEnvVars)
    appConfig + ("environment", JsArray(newEnvVars.map(tuple2jsObj).toSeq))
  }

  private def getImageAppConfig(image: ImageId): Future[JsObject] =
    getImageManifest(image)
      .map(v => (v \ "app").as[JsObject])

  private def podAppName(instanceId: String) = s"${config.instanceNamePrefix}-${instanceId}"

  private def newKeyPair: (RSAPrivateKey, RSAPublicKey) = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair
    (
      kp.getPrivate.asInstanceOf[RSAPrivateKey],
      kp.getPublic.asInstanceOf[RSAPublicKey]
    )
  }

  private def rktEnv(pairs: (String, String)*): JsArray = JsArray(
    pairs.map { case (k: String, v: String) => Json.obj("name" -> k, "value" -> v) })


}