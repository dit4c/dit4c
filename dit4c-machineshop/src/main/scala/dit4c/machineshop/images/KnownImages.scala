package dit4c.machineshop.images

import scalax.file.Path

class KnownImages(backingFile: Path) extends Iterable[KnownImage] {

  type ImageRef = (String, String)

  import spray.json._

  object KnownImageProtocol extends DefaultJsonProtocol {
    implicit val knownImageFormat = jsonFormat3(KnownImage)
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

  def iterator = read.toList.sortBy(_.displayName).toIterator

  private def write(images: Set[KnownImage]) = {
    backingFile.outputStream().write(images.toJson.prettyPrint)
  }

  private def read: Set[KnownImage] = {
    val images =
      backingFile.inputStream().string.parseJson.convertTo[List[KnownImage]]
    images.toSet
  }

}

case class KnownImage(displayName: String, repository: String, tag: String) {
  
  def ref = (repository, tag)
  
}