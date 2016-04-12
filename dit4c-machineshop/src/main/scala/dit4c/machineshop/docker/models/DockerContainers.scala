package dit4c.machineshop.docker.models

import scala.concurrent.Future

trait DockerContainers {
  type DockerImage = String

  def create(name: String, image: DockerImage, sharedWritable: Boolean = false): Future[DockerContainer]
  def list: Future[Seq[DockerContainer]]
}