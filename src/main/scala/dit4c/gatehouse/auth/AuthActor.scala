package dit4c.gatehouse.auth

import java.io.FileInputStream
import java.io.File
import java.text.ParseException
import akka.actor.Actor
import scala.util.Try
import akka.event.Logging
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import akka.actor.ActorRef
import akka.actor.Cancellable

class AuthActor(publicKeySource: java.net.URI) extends Actor with SignatureCheckerProvider {
  override val log = Logging(context.system, this)

  import AuthActor._

  val authorizationChecker = new AuthorizationChecker

  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system

  type AuthChecker = AuthCheck => AuthResponse
  type QueuedCheck = (ActorRef, AuthCheck)

  /* Signature Checker Updates */
  object UpdateSignatureChecker
  case class ReplaceSignatureChecker(sc: SignatureChecker)
  val keyUpdateInterval = Duration.create(1, TimeUnit.MINUTES)
  val base: Receive = {
    case UpdateSignatureChecker =>
      createSignatureChecker(publicKeySource)
        .foreach { sc =>
          context.self ! ReplaceSignatureChecker(sc)
        }
  }

  var scheduledCheck: Option[Cancellable] = None

  override def preStart = {
    // Schedule updates
    scheduledCheck = Some(
      context.system.scheduler.schedule(
        Duration.Zero,
        keyUpdateInterval,
        context.self,
        UpdateSignatureChecker))
  }

  override def postStop = {
    scheduledCheck.foreach(_.cancel)
  }

  val receive: Receive = queueChecks(Nil)

  def queueChecks(queue: Seq[QueuedCheck]): Receive = base.orElse {
    // Queue checks, successively altering state to add to the queue
    case check: AuthCheck =>
      context.become(queueChecks(queue :+ (sender, check)))
    // When we eventually get a replacement, dequeue and switch to new state
    case ReplaceSignatureChecker(sc) =>
      transition(authCheck(sc), queue)
  }

  def performChecks(checker: AuthChecker): Receive = base.orElse {
    // Perform the check immediately
    case query: AuthCheck =>
      sender ! checker(query)
    // Replace checker
    case ReplaceSignatureChecker(sc) =>
      transition(authCheck(sc), Nil)
  }

  private def transition(checker: AuthChecker, queue: Seq[QueuedCheck]) {
    context.become(performChecks(checker))
    queue.foreach { case (qSender, qCheck) =>
      qSender ! checker(qCheck)
    }
  }

  private def authCheck(sc: SignatureChecker)(check: AuthCheck): AuthResponse =
    sc(check.jwt)
      // If signature check passes, check authorization
      .right
      .flatMap { _ => authorizationChecker(check.jwt, check.containerName) }
      .fold(
        reason => AccessDenied(reason),
        _ => AccessGranted
      )

}


object AuthActor {

  case class AuthCheck(val jwt: String, val containerName: String)

  sealed trait AuthResponse
  object AccessGranted extends AuthResponse
  case class AccessDenied(reason: String) extends AuthResponse
}
