package dit4c.machineshop.docker.models

import scala.concurrent.Future

trait DockerContainers {
  def create(name: String): Future[DockerContainer]
  def list: Future[Seq[DockerContainer]]
}