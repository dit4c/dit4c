package dit4c.machineshop.docker.models

import scala.concurrent.Future
import spray.http.HttpResponse
import spray.http.HttpRequest

trait DockerContainer {

  def id: String
  def name: String
  def status: ContainerStatus

  lazy val isRunning = status == ContainerStatus.Running

  def refresh: Future[DockerContainer]

}