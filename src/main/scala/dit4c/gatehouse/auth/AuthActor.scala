package dit4c.gatehouse.auth

import java.io.FileInputStream
import java.io.File
import akka.actor.Actor

class AuthActor(publicKeySource: File) extends Actor {

  val signatureChecker: SignatureChecker = {
    import KeyLoader._
    new SignatureChecker(KeyLoader(new FileInputStream(publicKeySource)))
  }

  val authorizationChecker = new AuthorizationChecker

  import AuthActor._

  val receive: Receive = {
    case AuthCheck(jwt, containerName) =>
      sender ! doAuthCheck(jwt, containerName)
  }

  def doAuthCheck(jwt: String, containerName: String): AuthResponse =
    if (signatureChecker(jwt)) {
      if (authorizationChecker(jwt, containerName)) {
        AccessGranted()
      } else {
        AccessDenied("Invalid authorization token.")
      }
    } else {
      AccessDenied("Invalid authorization token.")
    }

}


object AuthActor {

  case class AuthCheck(jwt: String, containerName: String)

  sealed trait AuthResponse
  case class AccessGranted() extends AuthResponse
  case class AccessDenied(reason: String) extends AuthResponse
}
