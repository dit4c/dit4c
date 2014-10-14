package dit4c.machineshop.images

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import org.specs2.mutable.Specification
import akka.util.Timeout
import akka.testkit.TestActorRef
import dit4c.machineshop.docker.DockerClient
import scalax.file.ramfs.RamFileSystem
import dit4c.machineshop.docker.models.DockerImages
import akka.actor.ActorSystem
import dit4c.machineshop.docker.models.DockerContainers
import akka.pattern.ask
import scala.concurrent.Future
import scala.util.Success
import org.specs2.mock._
import java.util.UUID
import dit4c.machineshop.docker.models._
import scala.util.Random.shuffle
import scala.concurrent.Await.result
import java.util.Calendar

class ImageMonitoringActorSpec extends Specification with Mockito {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem()
  implicit val timeoutDuration = new FiniteDuration(5, TimeUnit.SECONDS)
  implicit val timeout = Timeout(timeoutDuration)

  import ImageMonitoringActor._

  "ImageMonitoringActor" >> {

    "adds images" >> {
      val actorRef = newActor

      val future = actorRef ? AddImage("Fedora", "fedora", "20")
      future.value.get match {
        case Success(AddingImage(image)) =>
          image.displayName must_== "Fedora"
          image.repository must_== "fedora"
          image.tag must_== "20"
        case other => failure("Unexpected: "+other)
      }
      done
    }
    
    "declines duplicate images" >> {
      val knownImages = ephemeralKnownImages
      val actorRef = newActor(knownImages)
      knownImages += KnownImage("Fedora", "fedora", "20")

      {
        val future = actorRef ? AddImage("Fedora 20", "fedora", "20")
        future.value.get match {
          case Success(ConflictingImages(Seq(image))) =>
            image.displayName must_== "Fedora"
            image.repository must_== "fedora"
            image.tag must_== "20"
        }
      }
        
      {
        val future = actorRef ? AddImage("Fedora", "fedora", "latest")
        future.value.get match {
          case Success(ConflictingImages(Seq(image))) =>
            image.displayName must_== "Fedora"
            image.repository must_== "fedora"
            image.tag must_== "20"
        }
      }
    }
    
    "pulls images" >> {
      val knownImages = ephemeralKnownImages
      val dockerClient = spy(new MockDockerClient(knownImages))
      val actorRef = newActor(knownImages, dockerClient)
      val knownImage = KnownImage("Fedora", "fedora", "20")
      knownImages += knownImage

      val future = actorRef ? PullImage(knownImage.id)
      future.value.get match {
        case Success(PullingImage(image)) =>
          image.displayName must_== knownImage.displayName
          image.repository must_== knownImage.repository
          image.tag must_== knownImage.tag
        case other => failure("Unexpected: "+other)
      }
      
      there was one(dockerClient.images).pull("fedora", "20")
    }
    
    "lists images" >> {
      val knownImages = ephemeralKnownImages
      val actorRef = newActor(knownImages)
      val knownImageList = IndexedSeq(
        KnownImage("CentOS", "centos", "centos7"),
        KnownImage("Fedora 19", "fedora", "19"),
        KnownImage("Fedora 20", "fedora", "20"))
      shuffle(knownImageList).foreach(knownImages += _)

      val future = actorRef ? ListImages()
      result(future, timeoutDuration) match {
        case ImageList(images) => images.zipWithIndex.foreach {
          case (image, i) =>
            image.id          must beMatching("[a-z0-9]+")
            image.displayName must_== knownImageList(i).displayName
            image.repository  must_== knownImageList(i).repository
            image.tag         must_== knownImageList(i).tag
            image.metadata    must beSome
        }
      }
      done
    }
    
    "removes images" >> {
      val knownImages = ephemeralKnownImages
      val actorRef = newActor(knownImages)
      val knownImage = KnownImage("Fedora", "fedora", "20")
      knownImages += knownImage

      val future = actorRef ? RemoveImage(knownImage.id)
      future.value.get match {
        case Success(RemovingImage(image)) =>
          image.displayName must_== "Fedora"
          image.repository must_== "fedora"
          image.tag must_== "20"
        case other => failure("Unexpected: "+other)
      }
      done
    }

  }

  def newActor: TestActorRef[ImageMonitoringActor] =
    newActor(ephemeralKnownImages)

  def newActor(knownImages: KnownImages): TestActorRef[ImageMonitoringActor] =
    newActor(knownImages, new MockDockerClient(knownImages))

  def newActor(
      knownImages: KnownImages,
      dockerClient: DockerClient): TestActorRef[ImageMonitoringActor] =
    TestActorRef(new ImageMonitoringActor(knownImages, dockerClient))

  def ephemeralKnownImages =
    new KnownImages(RamFileSystem().fromString("/known_images.json"))

  class MockDockerClient(knownImages: KnownImages) extends DockerClient {
    override val images = spy(new MockDockerImages(knownImages))
    override val containers = spy(new MockDockerContainers)
  }
  
  class MockDockerImages(knownImages: KnownImages) extends DockerImages {
    class MockDockerImage(val id: String, val names: Set[String])
      extends DockerImage {
      
      val created = Calendar.getInstance
    }
    
    override def list = Future.successful(knownImages.map { image =>
      spy(new MockDockerImage(UUID.randomUUID.toString, Set(
        s"${image.repository}:${image.tag}"
      ))).asInstanceOf[DockerImage]
    }.toSeq)
    
    override def pull(imageName: String, tagName: String) =
      Future.successful(())
  }
  
  class MockDockerContainers extends DockerContainers {
    override def create(name: String, image: String) = ???
    override def list = ???
  }
  
}


