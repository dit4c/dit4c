package dit4c.machineshop.docker.models

import scala.concurrent.Future

trait DockerImages {

  def list: Future[Seq[DockerImage]]
  def pull(imageName: String, tagName: String = "latest"): Future[Unit]

}