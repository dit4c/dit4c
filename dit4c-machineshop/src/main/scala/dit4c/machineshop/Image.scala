package dit4c.machineshop

import java.util.Calendar

trait Image {
  def id: String
  def displayName: String
  def metadata: Option[ImageMetadata]
  def repository: String
  def tag: String
}

trait ImageMetadata {
  def id: String
  def created: Calendar
}