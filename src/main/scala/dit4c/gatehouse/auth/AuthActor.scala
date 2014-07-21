package dit4c.gatehouse.auth

import java.io.FileInputStream
import java.io.File
import java.text.ParseException
import akka.actor.Actor
import scala.util.Try
import akka.event.Logging
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

class AuthActor(publicKeySource: File) extends Actor {
  val log = Logging(context.system, this)

  val authorizationChecker = new AuthorizationChecker

  implicit val executionContext = context.system.dispatcher

  object UpdateSignatureChecker

  import AuthActor._

  override def preStart = {
    context.system.scheduler.schedule(
      Duration.Zero,
      Duration.create(1, TimeUnit.MINUTES),
      context.self, UpdateSignatureChecker)
  }

  val base: Receive = {
    case UpdateSignatureChecker =>
      SignatureCheckerProvider
        .fromFile(publicKeySource)
        .foreach { sc => context.become(performChecks(sc)) }
  }

  val receive: Receive = base.orElse {
    case _: AuthCheck =>
      sender ! AccessDenied("Keys not loaded yet.")
  }

  def performChecks(signatureChecker: SignatureChecker): Receive = base.orElse {
    case AuthCheck(jwt, containerName) =>
      sender ! authCheck(signatureChecker)(jwt, containerName)
  }

  def authCheck(sc: SignatureChecker)(jwt: String, cn: String): AuthResponse =
    sc(jwt)
      // If signature check passes, check authorization
      .right.flatMap { _ => authorizationChecker(jwt, cn) }
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
