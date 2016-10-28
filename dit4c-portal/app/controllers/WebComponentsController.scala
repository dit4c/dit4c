package controllers

import play.api.http.HeaderNames
import play.api.i18n.I18nSupport
import play.api.i18n.MessagesApi
import play.api.libs.Codecs
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.RequestHeader
import play.twirl.api.Html
import play.api.mvc.Result

class WebComponentsController(
    val messagesApi: MessagesApi)
    extends Controller
    with I18nSupport {

  val log = play.api.Logger(this.getClass)

  def component(name: String) = Action { request =>
    withEtagCheck(request) {
      reflectiveComponentLookup(name) match {
        case Some(f) => Ok(f(request, messagesApi))
        case None => NotFound
      }
    }
  }

  def withEtagCheck(request: RequestHeader)(block: => Result): Result = {
    val expectedETag =
      (new ETagBuilder())
        .including(request.acceptLanguages.mkString)
        .including(request.host)
        .including(request.uri)
        .build
    request.headers.get(HeaderNames.IF_NONE_MATCH) match {
      case Some(etag) if etag == expectedETag => NotModified
      case None => block.withHeaders(HeaderNames.ETAG -> expectedETag)
    }
  }

  private def reflectiveComponentLookup(name: String): Option[(RequestHeader, MessagesApi) => Html] = {
    import scala.reflect.runtime.{ universe => ru }
    try {
      val mirror = ru.runtimeMirror(getClass.getClassLoader)
      val module = mirror.staticModule(s"views.html.components.${templateName(name)}")
      val instance = mirror.reflectModule(module).instance
      val template = instance.asInstanceOf[play.twirl.api.Template2[RequestHeader, MessagesApi, Html]]
      Some(template.render)
    } catch {
      case e: Throwable =>
        log.warn(s"Web component not found: ${templateName(name)}", e)
        None
    }
  }

  /**
   * Safely turn component name into a potential template name
   * @param name    component name
   */
  private def templateName(name: String) =
    name.toLowerCase
      .filter { c => c.isLetterOrDigit || c == '-' }
      .replace('-','_')

  private class ETagBuilder(initialData: Array[Byte] = Array.empty[Byte]) {
    def including(data: Array[Byte]): ETagBuilder = new ETagBuilder(initialData ++ data)
    def including(data: String): ETagBuilder = including(data.getBytes)
    def build = Codecs.sha1(initialData)
  }

}