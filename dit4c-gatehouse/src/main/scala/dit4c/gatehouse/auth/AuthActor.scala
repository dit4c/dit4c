package dit4c.gatehouse.auth

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor._
import akka.event.Logging

class AuthActor(publicKeySource: java.net.URI, keyUpdateInterval: FiniteDuration)
    extends Actor with SignatureCheckerProvider {
  override val log = Logging(context.system, this)

  import AuthActor._

  val authorizationChecker = new AuthorizationChecker

  implicit val executionContext = context.system.dispatcher
  implicit val actorRefFactory = context.system

  type AuthChecker = AuthCheck => AuthResponse
  type QueuedCheck = (ActorRef, AuthCheck)

  /* Signature Checker Updates */
  case class UpdateSignatureChecker(retryCount: Int = 0)
  case class ReplaceSignatureChecker(sc: SignatureChecker)
  val base: Receive = {
    case UpdateSignatureChecker(retries) =>
      createSignatureChecker(publicKeySource).onComplete {
        case Success(sc) =>
          context.self ! ReplaceSignatureChecker(sc)
        case Failure(e) =>
          val delay = 1.second * math.pow(2, retries).toInt
          log.warning(
              s"Failure generating signature checker: $e\nRetry in $delay.")
          context.system.scheduler.scheduleOnce(
            delay,
            context.self,
            UpdateSignatureChecker(retries + 1))
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
        UpdateSignatureChecker()))
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
