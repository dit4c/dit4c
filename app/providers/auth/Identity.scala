package providers.auth

trait Identity {
  def uniqueId: String
}

trait EmailIdentity {
  def emailAddress: String
}

trait NamedIdentity {
  def name: String
}