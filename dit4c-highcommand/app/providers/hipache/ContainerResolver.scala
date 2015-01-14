package providers.hipache

class ContainerResolver(app: play.api.Application) {
  def asFrontend(container: models.Container): Hipache.Frontend =
    Hipache.Frontend(dnsLabel(container),
        s"${dnsLabel(container)}.${baseDomain}")

  def asUrl(container: models.Container): java.net.URL = 
    new java.net.URL(s"$scheme://${asFrontend(container).domain}/")

  protected def dnsLabel(container: models.Container): String =
    containerPrefix + container.id

  private def scheme = baseUrl.getScheme
  private def baseDomain = baseUrl.getHost
    
  private val baseUrl = {
    val baseUrlKey = "application.baseUrl"
    try {
      app.configuration.getString(baseUrlKey)
        .map(new java.net.URI(_))
        .get
    } catch {
      case e: java.util.NoSuchElementException =>
        throw new RuntimeException(s"$baseUrlKey must be specified", e)
      case e: java.net.URISyntaxException =>
        throw new RuntimeException(s"$baseUrlKey must be a valid URI", e)
    }
  }

  private val containerPrefix = 
    app.configuration.getString("container.prefix").getOrElse("c-")
}