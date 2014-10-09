package dit4c.machineshop.models

import scalax.file.Path

class KnownImages(backingFile: Path) extends Iterable[KnownImage] {

  import spray.json._

  object KnownImageProtocol extends DefaultJsonProtocol {
    implicit val knownImageFormat = jsonFormat2(KnownImage)
  }

  import KnownImageProtocol._

  if (!backingFile.exists) {
    backingFile.createFile()
    write(Set.empty)
  }

  def +=(image: KnownImage): KnownImages = {
    write(read + image)
    this
  }

  def -=(image: KnownImage): KnownImages = {
    write(read - image)
    this
  }

  def iterator = read.toIterator

  private def write(images: Set[KnownImage]) = {
    backingFile.outputStream().write(images.toJson.prettyPrint)
  }

  private def read: Set[KnownImage] = {
    backingFile.inputStream().string.parseJson.convertTo[Set[KnownImage]]
  }

}

case class KnownImage(displayName: String, tagName: String)