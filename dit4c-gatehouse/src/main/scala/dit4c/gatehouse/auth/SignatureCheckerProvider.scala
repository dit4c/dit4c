package dit4c.gatehouse.auth

import java.io.File
import java.io.FileInputStream
import java.text.ParseException
import scala.concurrent._
import spray.http._
import spray.client.pipelining._
import akka.actor.ActorRefFactory

trait SignatureCheckerProvider {

  def log: akka.event.LoggingAdapter
  implicit def executionContext: ExecutionContext
  implicit def actorRefFactory: ActorRefFactory

  def createSignatureChecker(publicKeyLocation: java.net.URI): Future[SignatureChecker] = {
    import KeyLoader._
    log.info(s"Retrieving keys from $publicKeyLocation")
    if (publicKeyLocation.isAbsolute()) {
      pipeline(Get(publicKeyLocation.toASCIIString)).map { content =>
        new SignatureChecker(KeyLoader(content))
      }
    } else {
      // It's a file, so fetch directly
      future {
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

  protected def pipeline = sendReceive ~> unmarshal[String]

}