package dit4c.gatehouse.docker

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.event.Logging
import com.spotify.docker.client.DefaultDockerClient
import spray.http._
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

  def containerPorts = Future {
    dockerClient.listContainers().toSeq.flatMap { c =>
      val names = c.names().toSeq.map(_.stripPrefix("/"))
      val ports = c.ports.toSeq
      POTENTIAL_SERVICE_PORTS
        .map(p => ports.find(_.getPrivatePort == p))
        .flatten.headOption.toSeq
        .flatMap(mapping => names.toSeq.map(n => (n, mapping.getPublicPort)) )
    }.filter(_._1.isValidContainerName).toMap
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
