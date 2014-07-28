package providers.auth

trait Identity {
  def uniqueId: String
  def name: Option[String]
  def emailAddress: Option[String]
}