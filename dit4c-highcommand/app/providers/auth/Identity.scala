package providers.auth

trait Identity {
  def uniqueId: String
  def name: Option[String]
  def emailAddress: Option[String]

  override def toString = (uniqueId, name, emailAddress).toString
}