package providers

trait WebConfig {
  def siteTitle: Option[String]
  def frontpageLogo: Option[java.net.URL]
  def frontpageHtml: Option[play.twirl.api.Html]
  def googleAnalyticsCode: Option[String]
}
