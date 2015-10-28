package dit4c.gatehouse.docker

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject
import java.net.URI
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.api.model.InternetProtocol

class DockerClient(val uri: java.net.URI) {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val POTENTIAL_SERVICE_PORTS = Seq(80, 8080, 8888)

  val dockerClientConfig = DockerClientConfig.createDefaultConfigBuilder
    .withUri(uri.toASCIIString)
    .build
  val dockerClient = DockerClientBuilder.getInstance(dockerClientConfig).build

  def containerPort(containerId: String): Future[Option[String]] =
    Future {
      dockerClient.inspectContainerCmd(containerId).exec
    }.map { info =>
      val exposedPorts = info.getConfig.getExposedPorts.toSet
        .filter(_.getProtocol == InternetProtocol.TCP)
        .map(_.getPort)
      POTENTIAL_SERVICE_PORTS
        .filter(exposedPorts.contains)
        .headOption
        .map(info.getNetworkSettings.getIpAddress+":"+_)
    }

  def containerPorts =
    Future(dockerClient.listContainersCmd.exec.toSeq).flatMap { containers =>
      val nameMap =
        containers.flatMap { c =>
          c.getNames.toSeq
            .map(_.stripPrefix("/"))
            .map((_, c.getId))
        }.filter(_._1.isValidContainerName).toMap
      // Future id => port map
      val fPortMap = Future.sequence {
          // Futures for each container
          nameMap.values.toSet.map { id: String =>
            // Get container port, wrapping name into the Option response
            containerPort(id).map(maybePort => maybePort.map { (id, _) })
          }
        }.map(_.flatten.toMap) // Turn into map, removing None lookups
      // Merge the names and ports
      fPortMap.map { portMap =>
        nameMap
          .filter { case (name, id) => portMap.contains(id) }
          .map { case (name, id) =>
            (name, portMap(id))
          }
      }
    }

  implicit class ContainerNameTester(str: String) {

    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidContainerName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }

  }

}
