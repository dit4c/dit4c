package dit4c.machineshop

trait Image {
  def id: String
  def displayName: String
  def metadata: Option[ImageMetadata]
  def repository: String
  def tag: String
}

trait ImageMetadata {
  def id: String
}