package dit4c.machineshop.docker.models

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.`package`.DurationInt
import akka.stream.scaladsl.Source
import akka.util.ByteString

trait DockerContainer {

  def id: String
  def name: String
  def status: ContainerStatus

  lazy val isRunning = status == ContainerStatus.Running

  def refresh: Future[DockerContainer]
  def start: Future[DockerContainer]
  def stop(timeout: Duration = DurationInt(1).second): Future[DockerContainer]
  def export: Future[Source[ByteString, Future[Long]]]
  def delete: Future[Unit]

}
