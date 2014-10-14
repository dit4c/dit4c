package dit4c.machineshop.images

import dit4c.machineshop.{Image, ImageMetadata}
import dit4c.machineshop.docker.DockerClient
import dit4c.machineshop.docker.models.DockerImage
import akka.actor.Actor
import akka.event.Logging

class ImageMonitoringActor(knownImages: KnownImages, dockerClient: DockerClient)
    extends Actor {
  val log = Logging(context.system, this)

  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system

  import ImageMonitoringActor._

  val receive: Receive = {
    case AddImage(displayName, repository, tag) =>
      val conflictingImages = knownImages.find(_.displayName == displayName) ++ 
                              knownImages.find(_.ref == (repository, tag))
      sender ! (conflictingImages match {
        case Nil =>
          knownImages += KnownImage(displayName, repository, tag)
          dockerClient.images.pull(repository, tag)
          AddingImage(KnownImage(displayName, repository, tag))
        case images =>
          ConflictingImages(images.map(new ImageImpl(_)).toSeq)
      })

    case PullImage(id) =>
      knownImages.find(i => i.id == id) match {
        case Some(knownImage: KnownImage) =>
          dockerClient.images.pull(knownImage.repository, knownImage.tag)
          sender ! PullingImage(knownImage)
        case None =>
          sender ! UnknownImage(id)
      }

    case ListImages() => {
      val backTo = sender
      val ki = knownImages.toSeq
      for (
        dockerImages <- dockerClient.images.list
      ) yield {
        def imageMetadata(i: KnownImage): Option[ImageMetadata] = 
          dockerImages.find {
            _.names.exists(_ == i.repository+":"+i.tag)
          }.map(di2imi)
        def toImage(ki: KnownImage) =
          new ImageImpl(ki, imageMetadata(ki))
        backTo ! ImageList(ki.map(toImage))
      }
    }
    
    case RemoveImage(id) =>
      knownImages.find(i => i.id == id) match {
        case Some(knownImage: KnownImage) =>
          knownImages -= knownImage
          sender ! RemovingImage(knownImage)
        case None =>
          sender ! UnknownImage(id)
      }
  }

}

object ImageMonitoringActor {
  
  implicit def ki2ii(ki: KnownImage): Image = new ImageImpl(ki)
  
  implicit def di2imi(di: DockerImage): ImageMetadata =
    ImageMetadataImpl(di.id)
  
  case class ImageImpl(
      val id: String,
      val displayName: String,
      val repository: String,
      val tag: String,
      val metadata: Option[ImageMetadata]) extends Image {
    
    def this(ki: KnownImage, metadata: Option[ImageMetadata] = None) =
      this(ki.id, ki.displayName, ki.repository, ki.tag, metadata)
      
  }
  
  case class ImageMetadataImpl(
    val id:String) extends ImageMetadata
  
  case class AddImage(displayName: String, repository: String, tag: String)
  case class PullImage(id: String)
  case class ListImages()
  case class RemoveImage(id: String)

  case class AddingImage(image: Image)
  case class ConflictingImages(images: Seq[Image])
  case class PullingImage(image: Image)
  case class RemovingImage(image: Image)
  case class ImageList(images: Seq[Image])
  case class UnknownImage(id: String)

}
