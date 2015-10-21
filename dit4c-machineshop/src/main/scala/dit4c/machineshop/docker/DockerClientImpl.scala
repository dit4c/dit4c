package dit4c.machineshop.docker

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import com.github.dockerjava.api.model.Link
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.command.PullImageResultCallback
import akka.http.scaladsl.model.Uri
import akka.stream.io.InputStreamSource
import akka.stream.scaladsl.Source
import dit4c.machineshop.docker.models.ContainerLink
import dit4c.machineshop.docker.models.ContainerStatus
import dit4c.machineshop.docker.models.DockerContainer
import dit4c.machineshop.docker.models.DockerContainers
import dit4c.machineshop.docker.models.DockerImage
import dit4c.machineshop.docker.models.DockerImages
import akka.util.ByteString
import java.io.BufferedInputStream
import akka.stream.scaladsl.Flow
import dit4c.machineshop.docker.utils.DiskBasedChunker
import akka.stream.stage.PushStage
import akka.stream.stage.Context

class DockerClientImpl(
    val baseUrl: Uri,
    val newContainerLinks: Seq[ContainerLink] = Nil) extends DockerClient {

  // Uncapped ExecutionContext, so we can do sync network requests
  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  val docker: com.github.dockerjava.api.DockerClient = {
    val config =
      DockerClientConfig.createDefaultConfigBuilder
        .withUri(baseUrl.toString)
        .build
    DockerClientBuilder.getInstance(config).build
  }

  override val images = ImagesImpl
  override val containers = ContainersImpl

  case class ImageImpl(
      val id: String,
      val names: Set[String],
      val created: Calendar) extends DockerImage

  object ImagesImpl extends DockerImages {

    def list = Future({
      docker.listImagesCmd.exec.toIterable.map { img =>
        ImageImpl(
            img.getId,
            img.getRepoTags.toSet.filter(_ != "<none>:<none>"),
            {
              val c = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
              c.setTimeInMillis(img.getCreated * 1000)
              c
            })
      }.filter(!_.names.isEmpty).toSeq
    })(ec)

    def pull(imageName: String, tagName: String) = Future({
      docker.pullImageCmd(imageName).withTag(tagName)
        .exec(new PullImageResultCallback()).awaitSuccess
    })(ec)

  }



  class ContainerImpl(val id: String, val name: String, val status: ContainerStatus) extends DockerContainer {

    override def refresh = Future({
      val c = docker.inspectContainerCmd(id).exec
      val status =
        if (c.getState.isRunning) ContainerStatus.Running
        else ContainerStatus.Stopped
      new ContainerImpl(id, name, status)
    })(ec)

    override def start = Future({
      docker.startContainerCmd(id).exec
    })(ec).flatMap(_ => this.refresh)

    override def stop(timeout: Duration) = Future({
      docker.stopContainerCmd(id)
        .withTimeout(Math.max(0, timeout.toSeconds.toInt)).exec
    })(ec).flatMap(_ => this.refresh)

    override def export: Future[Source[ByteString, Future[Long]]] = Future({
      docker.commitCmd(id).exec
    })(ec).map { imageId =>
      lazy val fRemoveImage = Future(docker.removeImageCmd(imageId).exec)(ec)
      InputStreamSource(() =>docker.saveImageCmd(imageId).exec)
        .transform(() => new DiskBasedChunker(65536))
        .map(v => { fRemoveImage; v }) // Remove image onces stream starts
    }

    override def delete = Future({
      docker.removeContainerCmd(id).withRemoveVolumes(true).exec
      () // Need to return Unit, not Void
    })(ec)

  }

  object ContainersImpl extends DockerContainers {

    override def create(name: String, image: DockerImage) = Future({
      if (!name.isValidContainerName) {
        throw new IllegalArgumentException(
            "Name must be a valid lower-case DNS label")
      }
      val c = docker.createContainerCmd(image)
        .withName(name)
        .withHostName(name)
        .withTty(true)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withCpuShares(2)
        .withLinks(newContainerLinks.map(l => new Link(l.outside, l.inside)): _*)
        .withRestartPolicy(RestartPolicy.alwaysRestart)
        .exec
      new ContainerImpl(c.getId, name, ContainerStatus.Stopped)
    })(ec)

    override def list = Future({
      docker.listContainersCmd.withShowAll(true)
        .exec.toSeq
        .map { c =>
          // Get the first name, without the slash
          // (Multiple names are possible, but first should be native name.)
          val name: String = c.getNames.toSeq
            .filter(_.startsWith("/"))
            .map(_.stripPrefix("/"))
            .head
          val status =
            if (c.getStatus.matches("Up .*")) ContainerStatus.Running
            else ContainerStatus.Stopped
          new ContainerImpl(c.getId, name, status)
        }
        .filter(_.name.isValidContainerName)
    })(ec)

  }

  implicit class ContainerNameTester(str: String) {
    // Same as domain name, but use of capitals is prohibited because container
    // names are case-sensitive while host names should be case-insensitive.
    def isValidContainerName = {
      !str.isEmpty &&
      str.length <= 63 &&
      !str.startsWith("-") &&
      !str.endsWith("-") &&
      str.matches("[a-z0-9\\-]+")
    }
  }

}
