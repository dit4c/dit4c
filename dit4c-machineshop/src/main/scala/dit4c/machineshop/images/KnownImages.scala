package dit4c.machineshop.images

import scalax.file.Path

class KnownImages(backingFile: Path) extends Iterable[KnownImage] {

  import spray.json._

  object KnownImageProtocol extends DefaultJsonProtocol {
    implicit val knownImageFormat = jsonFormat2(KnownImage)
  }

  import KnownImageProtocol._

  if (!backingFile.exists) {
    backingFile.createFile()
    write(Map.empty)
  }

  def +=(image: KnownImage): KnownImages = {
    write(read + (image.displayName -> image))
    this
  }

  def -=(image: KnownImage): KnownImages = {
    write(read - image.displayName)
    this
  }

  def iterator = read.values.toList.sortBy(_.displayName).toIterator

  private def write(imageMap: Map[String,KnownImage]) = {
    val images = imageMap.values
    backingFile.outputStream().write(images.toJson.prettyPrint)
  }

  private def read: Map[String,KnownImage] = {
    val images =
      backingFile.inputStream().string.parseJson.convertTo[List[KnownImage]]
    images.map(i => (i.displayName -> i)).toMap
  }

}

case class KnownImage(displayName: String, tagName: String)