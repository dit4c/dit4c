package dit4c.machineshop.docker.models

trait DockerImage {

  def id: String
  def names: Set[String]

}