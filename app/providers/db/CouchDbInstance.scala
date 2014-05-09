package providers.db

trait CouchDbInstance {

  protected def host: String
  protected def port: Int

}