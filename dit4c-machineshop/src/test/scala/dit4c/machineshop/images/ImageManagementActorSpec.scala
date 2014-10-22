package dit4c.machineshop.images

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import org.specs2.mutable.Specification
import akka.util.Timeout
import dit4c.machineshop.docker.DockerClient
import scalax.file.ramfs.RamFileSystem
import dit4c.machineshop.docker.models.DockerImages
import akka.actor.{ActorSystem, ActorRef, Props}
import dit4c.machineshop.Image
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
import akka.testkit.TestKit
import org.specs2.time.{Duration => SpecsDuration}
import scala.concurrent.duration.{Duration, FiniteDuration}

class ImageManagementActorSpec extends Specification with Mockito {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem()
  implicit val timeoutDuration = new FiniteDuration(5, TimeUnit.SECONDS)
  implicit val timeout = Timeout(timeoutDuration)

  private implicit def specsDuration2scala(d: SpecsDuration): FiniteDuration =
    Duration.create(d.inMilliseconds, "millis")

  import ImageManagementActor._

  "ImageManagementActor" >> {

    "adds images" >> {
      val actorRef = newActor

      val future = actorRef ? AddImage("Fedora", "fedora", "20")
      result(future, timeoutDuration) match {
        case AddedImage(image) =>
          image.displayName must_== "Fedora"
          image.repository must_== "fedora"
          image.tag must_== "20"
        case other => failure("Unexpected: "+other)
      }
      done
    }

    "declines duplicate images" >> {
      val knownImages = ephemeralKnownImages
      knownImages += KnownImage("Fedora", "fedora", "20")
      val actorRef = newActor(knownImages)

      {
        val future = actorRef ? AddImage("Fedora 20", "fedora", "20")
        result(future, timeoutDuration) match {
          case ConflictingImages(Seq(image)) =>
            image.displayName must_== "Fedora"
            image.repository must_== "fedora"
            image.tag must_== "20"
        }
      }

      {
        val future = actorRef ? AddImage("Fedora", "fedora", "latest")
        result(future, timeoutDuration) match {
          case ConflictingImages(Seq(image)) =>
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
      result(future, timeoutDuration) match {
        case PullingImage(image) =>
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
        case ImageList(images, _) => images.zipWithIndex.foreach {
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
      result(future, timeoutDuration) match {
        case RemovedImage(image) =>
          image.displayName must_== "Fedora"
          image.repository must_== "fedora"
          image.tag must_== "20"
        case other => failure("Unexpected: "+other)
      }
      done
    }

    "ImageUpdateActor" >> {

      case class MockImage(id: String) extends Image {
        def displayName: String = ???
        def metadata: Option[dit4c.machineshop.ImageMetadata] = ???
        def repository: String = ???
        def tag: String = ???
      }

      "on \"tick\" it requests image list from manager" >> {
        new TestKit(ActorSystem()) {
          try {
            val test = system.actorOf(Props(
              classOf[ImageUpdateActor], testActor))
            within (1.second) {
              test.tell("tick", null)
              expectMsg(ListImages())
            }
          } finally {
            system.shutdown()
          }
        }
        done
      }

      "on ImageList(images) it requests from manager a pull for each image" >> {
        new TestKit(ActorSystem()) {
          try {
            val test = system.actorOf(Props(
              classOf[ImageUpdateActor], testActor))
            val images = Seq(
              MockImage("foo"),
              MockImage("bar")
            )
            within (1.second) {
              test.tell(ImageList(images, "testStateId"), null)
              images.foreach { image =>
                expectMsg(PullImage(image.id))
              }
            }
          } finally {
            system.shutdown()
          }
        }
        done
      }
    }


  }

  def newActor: ActorRef =
    newActor(ephemeralKnownImages)

  def newActor(knownImages: KnownImages): ActorRef =
    newActor(knownImages, new MockDockerClient(knownImages))

  def newActor(
      knownImages: KnownImages,
      dockerClient: DockerClient): ActorRef =
    system.actorOf(
      Props(classOf[ImageManagementActor], knownImages, dockerClient, None))

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
