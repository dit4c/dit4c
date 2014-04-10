package dit4c.gatehouse.auth

import java.io.FileInputStream
import java.io.File
import java.text.ParseException
import akka.actor.Actor
import scala.util.Try
import akka.event.Logging

class AuthActor(publicKeySource: File) extends Actor {
  val log = Logging(context.system, this)

  val signatureChecker: SignatureChecker = {
    import KeyLoader._
    try {
      new SignatureChecker(KeyLoader(new FileInputStream(publicKeySource)))
    } catch {
      case e: ParseException =>
        log.error(
            s"No keys loaded. Unable to read public keys: ${e.getMessage}")
        new SignatureChecker(Nil)
    }
  }

  val authorizationChecker = new AuthorizationChecker

  import AuthActor._

  val receive: Receive = {
    case AuthCheck(jwt, containerName) =>
      sender ! doAuthCheck(jwt, containerName)
  }

  def doAuthCheck(jwt: String, containerName: String): AuthResponse =
    signatureChecker(jwt)
      // If signature check passes, check authorization
      .right.flatMap { _ => authorizationChecker(jwt, containerName) }
      .fold(
        reason => AccessDenied(reason),
        _ => AccessGranted
      )

}


object AuthActor {

  case class AuthCheck(jwt: String, containerName: String)

  sealed trait AuthResponse
  object AccessGranted extends AuthResponse
  case class AccessDenied(reason: String) extends AuthResponse
}
