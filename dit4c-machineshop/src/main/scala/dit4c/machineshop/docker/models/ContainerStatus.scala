package dit4c.machineshop.docker.models

sealed trait ContainerStatus

object ContainerStatus {
  object Running extends ContainerStatus
  object Stopped extends ContainerStatus
}