package dit4c.machineshop.docker.models

import java.util.Calendar

trait DockerImage {

  def id: String
  def names: Set[String]
  def created: Calendar

}