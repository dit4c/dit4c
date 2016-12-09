package controllers

import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.i18n._
import com.softwaremill.tagging._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import akka.actor._
import services._
import domain._
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.StatusCodes.ServerError
import akka.http.scaladsl.model.Uri
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.Silhouette
import utils.auth.DefaultEnv
import com.mohiva.play.silhouette.api.LoginEvent
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import com.mohiva.play.silhouette.impl.providers.SocialProvider
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfileBuilder
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import play.api.Environment
import play.api.Mode
import com.mohiva.play.silhouette.api.HandlerResult
import com.mohiva.play.silhouette.api.actions.SecuredRequest
import play.api.libs.streams.ActorFlow
import play.api.http.websocket.TextMessage
import akka.stream.Materializer
import play.api.libs.ws.WSClient
import play.api.libs.ws.StreamedResponse
import utils.oauth.AuthorizationCodeGenerator
import java.time.Instant
import scala.util._
import play.api.http.websocket.CloseMessage
import play.twirl.api.Html
import java.util.Base64
import akka.util.ByteString

class AccessPassController(
    val userSharder: ActorRef @@ UserSharder.type,
    val silhouette: Silhouette[DefaultEnv])(implicit system: ActorSystem, materializer: Materializer)
    extends Controller {
  import play.api.libs.concurrent.Execution.Implicits._

  def redeemAccessPass(schedulerId: String, encodedAccessPass: String) =
    silhouette.SecuredAction.async { implicit request =>
      implicit val timeout = Timeout(1.minute)
      for {
        signedData <- Future { ByteString(Base64.getUrlDecoder.decode(encodedAccessPass)) }
        queryMsg = UserSharder.Envelope(request.identity.id,
            UserAggregate.AddAccessPass(schedulerId, signedData))
        queryResponse <- (userSharder ? queryMsg)
      } yield queryResponse match {
        case UserAggregate.AccessPassAdded =>
          Redirect(routes.MainController.index())
        case UserAggregate.AccessPassRejected(reason) =>
          InternalServerError(reason)
      }
    }


}