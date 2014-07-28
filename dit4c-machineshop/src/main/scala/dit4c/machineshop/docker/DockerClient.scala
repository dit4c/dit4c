package dit4c.machineshop.docker

import dit4c.machineshop.docker.models.DockerContainers

trait DockerClient {

  def containers: DockerContainers

}