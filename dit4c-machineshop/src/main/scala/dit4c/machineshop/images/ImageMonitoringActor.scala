package dit4c.machineshop.images

import dit4c.machineshop.docker.DockerClient
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
          ConflictingImages(images.toSeq)
      })

    case PullImage(repository, tag) =>
      knownImages.find(i => i.repository == repository && i.tag == tag) match {
        case Some(knownImage: KnownImage) =>
          dockerClient.images.pull(repository, tag)
          sender ! PullingImage(knownImage)
        case None =>
          sender ! UnknownImage(repository, tag)
      }

    case ListImages() => {
      val backTo = sender
      val ki = knownImages.toSeq
      for (
        dockerImages <- dockerClient.images.list
      ) yield {
        def imageExists(i: KnownImage): Boolean = dockerImages.exists {
          _.names.exists(_ == i.repository+":"+i.tag)
        }
        backTo ! ImageList(ki.filter(imageExists))
      }
    }
    
    case RemoveImage(repository, tag) =>
      knownImages.find(i => i.repository == repository && i.tag == tag) match {
        case Some(knownImage: KnownImage) =>
          knownImages -= knownImage
          sender ! RemovingImage(knownImage)
        case None =>
          sender ! UnknownImage(repository, tag)
      }
  }

}

object ImageMonitoringActor {

  case class AddImage(displayName: String, repository: String, tag: String)
  case class PullImage(repository: String, tag: String)
  case class ListImages()
  case class RemoveImage(repository: String, tag: String)

  case class AddingImage(image: KnownImage)
  case class ConflictingImages(images: Seq[KnownImage])
  case class PullingImage(image: KnownImage)
  case class RemovingImage(image: KnownImage)
  case class ImageList(images: Seq[KnownImage])
  case class UnknownImage(repository: String, tag: String)

}
