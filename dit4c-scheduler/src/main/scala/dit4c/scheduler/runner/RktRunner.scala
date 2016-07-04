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

trait RktRunner {
  type ImageId = String

  def fetch(imageName: String): Future[ImageId]
  def start(
      instanceId: String,
      image: ImageId,
      callbackUrl: String): Future[RSAPublicKey]
  def stop(instanceId: String): Future[Unit]

}

class RktRunnerImpl(
    val ce: CommandExecutor,
    val rktDir: Path,
    val instanceNamePrefix: String = "dit4c-instance-")(
        implicit ec: ExecutionContext) extends RktRunner {

  if (!instanceNamePrefix.matches("""[a-z0-9\-]+"""))
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
      callbackUrl: String): Future[RSAPublicKey] =
    if (instanceId.matches("""[a-z0-9\-]+""")) {
      for {
        (manifestFile, publicKey) <-
          generateManifestFile(instanceId, image, callbackUrl)
        systemdRun <- privileged(systemdRunCmd)
        rkt <- rktCmd
        output <- ce(
            systemdRun ++
            Seq(s"--unit=${instanceNamePrefix}-${instanceId}.service") ++
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
          s"${instanceNamePrefix}-${instanceId}.service")
      }
      .map { _ => () }

  protected[runner] def listSystemdUnits: Future[Set[SystemdUnit]] =
    systemctlCmd
      .flatMap { bin => ce(bin :+ "list-units" :+ "--no-legend" :+ s"$instanceNamePrefix*") }
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
              var parts = line.split("""(\t|\s\s+)""").toList
              val (uuid :: appName :: _ :: _ :: state :: _) = parts
              m :+ RktPod(uuid, Set(appName), RktPod.States.fromString(state))
          }
        }.toSet
      }

  private def rktCmd = which("rkt").map(_ :+ s"--dir=$rktDir")

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
      callbackUrl: String): Future[(String, RSAPublicKey)] = {
    for {
      curlImageId <- fetch("quay.io/yss44/curl")
      socatImageId <- fetch("quay.io/ridero/socat")
      servicePort <- guessServicePort(image)
      (privateKey, publicKey) = newKeyPair
      token = jwtToken(instanceId, privateKey)
      proxyHostIP <- hostIp
      proxyHostPort = 20000 + Random.nextInt(10000)
      callbackPayload =
        Json.asciiStringify(
          JsString(
            Json.asciiStringify(
              Json.obj(
                  "uri" -> Uri(
                      scheme="http",
                      authority=Uri.Authority(
                          Uri.Host(proxyHostIP),
                          proxyHostPort)).toString))))
      manifest =
        s"""|{
            |    "acVersion": "0.8.4",
            |    "acKind": "PodManifest",
            |    "apps": [
            |        {
            |            "name": "${instanceNamePrefix}-${instanceId}",
            |            "image": {
            |                "id": "${image}"
            |            }
            |        },
            |        {
            |            "name": "callback-helper",
            |            "image": {
            |                "id": "${curlImageId}"
            |            },
            |            "app": {
            |                "exec": [
            |                    "watch", "-n", "60",
            |                    "curl", "-v",
            |                    "-X", "PUT",
            |                    "--retry", "10",
            |                    "-H", "Authorization: Bearer ${token}",
            |                    "-H", "Content-Type: application/json",
            |                    "-d", $callbackPayload,
            |                    "$callbackUrl"
            |                ],
            |                "group": "99",
            |                "user": "99"
            |            },
            |            "readOnlyRootFS": true
            |        },
            |        {
            |            "name": "pod-proxy",
            |            "image": {
            |                "id": "${socatImageId}"
            |            },
            |            "app": {
            |                "exec": [
            |                    "socat", "-d", "-d", "-d",
            |                    "TCP-LISTEN:20000,fork,reuseaddr",
            |                    "TCP:127.0.0.1:${servicePort}"
            |                ],
            |                "group": "99",
            |                "user": "99",
            |                "ports": [
            |                    {
            |                        "name": "pod-proxy-http",
            |                        "port": 20000,
            |                        "protocol": "tcp"
            |                    }
            |                ]
            |            },
            |            "readOnlyRootFS": true
            |        }
            |    ],
            |    "ports": [
            |        {
            |            "name": "pod-proxy-http",
            |            "hostPort": ${proxyHostPort}
            |        }
            |    ]
            |}""".stripMargin
      output <- ce(Seq("sh", "-c", Seq(
          "TMPFILE=$(mktemp --tmpdir manifest-json-XXXXXXXX)",
          "cat > $TMPFILE",
          "test -f $TMPFILE",
          "echo $TMPFILE").mkString(" && ")),
        new ByteArrayInputStream((manifest+"\n").getBytes))
      filepath = output.trim
    } yield (filepath, publicKey)
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
        "/sbin/ip route get 8.8.8.8"))
      .collect {
        case s if s.startsWith("8.8.8.8") =>
          s.lines.next.split(" ").last
      }

  private def jwtToken(id: String, key: RSAPrivateKey): String = {
    Jwt.encode(s"""{"id":"$id"}""", key, JwtAlgorithm.RSASHA512)
  }

}