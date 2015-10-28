package dit4c.gatehouse.docker

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import com.spotify.docker.client.DefaultDockerClient
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject
import java.net.URI

class DockerClient(val uri: java.net.URI) {

  implicit val system: ActorSystem = ActorSystem()
  implicit val timeout: Timeout = Timeout(15.seconds)
  import system.dispatcher // implicit execution context
  val log = Logging(system, getClass)

  val POTENTIAL_SERVICE_PORTS = Seq(80, 8080, 8888)

  val dockerClient = new DefaultDockerClient(uri)

  def containerPort(containerId: String): Future[Option[String]] =
    Future {
      dockerClient.inspectContainer(containerId)
    }.map { info =>
      val exposedPorts = info.config.exposedPorts.toSet
      POTENTIAL_SERVICE_PORTS
        .filter(p => exposedPorts.contains(s"$p/tcp"))
        .headOption
        .map(info.networkSettings.ipAddress+":"+_)
    }

  def containerPorts =
    Future(dockerClient.listContainers().toSeq).flatMap { containers =>
      val nameMap =
        containers.flatMap { c =>
          c.names.toSeq
            .map(_.stripPrefix("/"))
            .map((_, c.id))
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
