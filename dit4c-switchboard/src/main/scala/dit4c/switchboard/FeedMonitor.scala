package dit4c.switchboard

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger
import com.typesafe.scalalogging.Seq
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.apply
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.pattern.after
import akka.stream.Materializer
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import dit4c.common.AkkaHttpExtras
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json
import play.api.libs.json.JsValue

object FeedMonitor {
  def apply(
      config: Config,
      retryWait: FiniteDuration = 5.seconds)(eventProcessor: JsValue => Unit) (
          implicit system: ActorSystem,
          materializer: Materializer) : Unit = {
    implicit val ec = materializer.executionContext
    import Route._
    import dit4c.common.AkkaHttpExtras._
    lazy val logger = Logger(LoggerFactory.getLogger(this.getClass.getName))
    Http().singleResilientRequest(
        HttpRequest(uri = config.feed.toString),
        ClientConnectionSettings(system), None, system.log)
      .map { response =>
        response.entity match {
          case HttpEntity.Chunked(mimeType, parts) if mimeType.mediaType.value == "text/event-stream" =>
            parts
              .takeWithin(1.hour) // Avoid timeouts
              .map(_.data)
              .via(Framing.delimiter(ByteString("\n"), Int.MaxValue))
              .map(v => new String(v.decodeString(mimeType.charsetOption.map(_.value).getOrElse("utf-8"))))
              .filter(_.startsWith("data: "))
              .map(_.replaceFirst("data: ", ""))
              .map(Json.parse(_))
          case entity =>
            throw new Exception(
                "Feed should be a chunked EventSource stream: "+response)
        }
      }
      .flatMap { source =>
        source.runForeach(eventProcessor)
      }
      .onComplete {
        case Success(akka.Done) =>
          logger.warn("Disconnected from feed. Reconnecting...")
          FeedMonitor(config, retryWait)(eventProcessor)
        case Failure(e) =>
          logger.error(s"Feed connection error: $e")
          logger.error(s"Waiting $retryWait before retry...")
          after(retryWait, system.scheduler)(Future.successful(()))
            .onComplete { _ =>
              FeedMonitor(config, retryWait)(eventProcessor)
            }
      }
  }

}
