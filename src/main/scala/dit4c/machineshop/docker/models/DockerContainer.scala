package dit4c.machineshop.docker.models

class DockerContainer(val id: String, val name: String, status: ContainerStatus) {

  lazy val isRunning = status == ContainerStatus.Running

}