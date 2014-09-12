package dit4c.machineshop.docker

import dit4c.machineshop.docker.models._

trait DockerClient {

  def images: DockerImages

  def containers: DockerContainers

}