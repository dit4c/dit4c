package dit4c.gatehouse.auth

import java.io.File
import java.io.FileInputStream
import java.text.ParseException
import scala.concurrent._
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.actor.ActorSystem
import akka.stream._
import akka.http.scaladsl.model._

trait SignatureCheckerProvider {

  def log: akka.event.LoggingAdapter
  implicit def executionContext: ExecutionContext
  implicit def system: ActorSystem
  implicit lazy val materializer: Materializer = ActorMaterializer()

  def createSignatureChecker(publicKeyLocation: java.net.URI): Future[SignatureChecker] = {
    import KeyLoader._
    log.info(s"Retrieving keys from $publicKeyLocation")
    if (publicKeyLocation.isAbsolute()) {
      Http().singleRequest(HttpRequest(uri = Uri(publicKeyLocation.toASCIIString)))
        .flatMap(Unmarshal(_).to[String])
        .map { content =>
          val keys = KeyLoader(content)
          log.info(s"Retrieved ${keys.size} keys from $publicKeyLocation")
          new SignatureChecker(keys)
        }
    } else {
      // It's a file, so fetch directly
      Future {
        try {
          val fileInput = new FileInputStream(publicKeyLocation.getPath())
          new SignatureChecker(KeyLoader(fileInput))
        } catch {
          case e: ParseException =>
            log.warning(
                s"No keys loaded. Unable to read public keys: ${e.getMessage}")
            new SignatureChecker(Nil)
        }
      }
    }
  }

}
