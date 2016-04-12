package dit4c.machineshop.docker

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import com.github.dockerjava.api.model.Link
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.command.PullImageResultCallback
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import dit4c.machineshop.docker.models.ContainerStatus
import dit4c.machineshop.docker.models.DockerContainer
import dit4c.machineshop.docker.models.DockerContainers
import dit4c.machineshop.docker.models.DockerImage
import dit4c.machineshop.docker.models.DockerImages
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Promise
import akka.stream.stage.Context
import akka.stream.stage.PushStage

class DockerClientImpl(
    val dockerConfig: DockerClientConfig) extends DockerClient {

  // Uncapped ExecutionContext, so we can do sync network requests
  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  val docker: com.github.dockerjava.api.DockerClient =
    DockerClientBuilder.getInstance(dockerConfig).build

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
        if (c.getState.getRunning) ContainerStatus.Running
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

    override def export: Source[ByteString, Future[Long]] = {
      val byteCounter = new AtomicLong(0)
      val completerStage = new DockerClientImpl.CompleterStage[ByteString]()
      Source.fromFuture(Future {
        docker.commitCmd(id).exec
      }).flatMapConcat { imageId =>
        lazy val fRemoveImage = Future(docker.removeImageCmd(imageId).exec)(ec)
        StreamConverters.fromInputStream(() =>docker.saveImageCmd(imageId).exec)
          .map(v => { fRemoveImage; v }) // Remove image onces stream starts
      }.map { bs => byteCounter.addAndGet(bs.size); bs }
       .transform { () => completerStage }
       .mapMaterializedValue(_ => completerStage.future.map(_ => byteCounter.get))
    }

    override def delete = Future({
      docker.removeContainerCmd(id).withRemoveVolumes(true).exec
      () // Need to return Unit, not Void
    })(ec)

  }

  object ContainersImpl extends DockerContainers {

    override def create(
        name: String,
        image: DockerImage,
        sharedWritable: Boolean) = Future({
      if (!name.isValidContainerName) {
        throw new IllegalArgumentException(
            "Name must be a valid lower-case DNS label")
      }
      val sharedVolumeName = "dit4c_shared"
      try {
        docker.inspectVolumeCmd(sharedVolumeName).exec
      } catch {
        case _: com.github.dockerjava.api.exception.NotFoundException =>
          docker.createVolumeCmd().withName(sharedVolumeName).exec
      }

      val c = docker.createContainerCmd(image)
        .withName(name)
        .withHostName(name)
        .withTty(true)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withCpuShares(2)
        .withRestartPolicy(RestartPolicy.alwaysRestart)
        .withBinds(new Bind(
            sharedVolumeName,
            new Volume("/mnt/shared"),
            if (sharedWritable) AccessMode.rw else AccessMode.ro))
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

object DockerClientImpl {
  def apply(maybeUri: Option[java.net.URI]): DockerClient =
    new DockerClientImpl(
        DockerClientConfig.createDefaultConfigBuilder
          .withUri(maybeUri)
          .fixTLS
          .build)

  implicit private class BuilderAddons(b: DockerClientConfigBuilder) {
    def withUri(maybeUri: Option[java.net.URI]): DockerClientConfigBuilder =
      maybeUri.map(uri => b.withDockerHost(uri.toASCIIString)).getOrElse(b)
    def fixTLS: DockerClientConfigBuilder =
      b.withDockerTlsVerify(false).build.getDockerHost match {
        case uri if uri.getScheme == "unix" =>
          b.withDockerTlsVerify(false)
        case uri if uri.getScheme == "tcp" && uri.getPort == 2376 =>
          b.withDockerTlsVerify(true)
        case uri if uri.getScheme == "tcp" && uri.getPort == 2375 =>
          b.withDockerTlsVerify(false)
      }
  }

  protected class CompleterStage[T] extends PushStage[T, T] {
    private val p = Promise[Unit]()
    def future = p.future
    def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)
    override def onUpstreamFailure(cause: Throwable, ctx: Context[T]) = {
      p.failure(cause)
      super.onUpstreamFailure(cause, ctx)
    }
    override def onUpstreamFinish(ctx: Context[T]) = {
      p.success(())
      super.onUpstreamFinish(ctx)
    }
  }

}
