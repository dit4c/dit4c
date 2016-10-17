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

class MainController(
    val environment: Environment,
    val messagesApi: MessagesApi,
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager,
    val userAggregateManager: ActorRef @@ UserAggregateManager,
    val silhouette: Silhouette[DefaultEnv],
    val identityService: IdentityService,
    val oauthDataHandler: InstanceOAuthDataHandler,
    val socialProviders: SocialProviderRegistry)(implicit system: ActorSystem, materializer: Materializer)
    extends Controller
    with I18nSupport {

  import play.api.libs.concurrent.Execution.Implicits._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  val log = play.Logger.underlying

  def index = silhouette.UserAwareAction { implicit request =>
    request.identity match {
      case Some(IdentityService.User(_, _)) =>
        Ok(views.html.index(imageLookup.keys.toList.sorted))
      case None =>
        Ok(views.html.login(noneIfProd(loginForm), socialProviders))
    }
  }

  def getInstances = WebSocket { requestHeader =>
    implicit val dummyRequest = Request(requestHeader, AnyContentAsEmpty)
    silhouette.SecuredRequestHandler { securedRequest: SecuredRequest[DefaultEnv, AnyContent] =>
      Future.successful(HandlerResult(Ok, Some(securedRequest.identity)))
    }.map {
      case HandlerResult(r, Some(user)) =>
        import play.api.http.websocket._
        Right(ActorFlow.actorRef[Message, Message] { out =>
          Props(classOf[GetInstancesActor], out, user, userAggregateManager, instanceAggregateManager)
        })
      case HandlerResult(r, None) => Left(r)
    }
  }

  def newInstance = silhouette.SecuredAction.async { implicit request =>
    newInstanceForm(request.identity.id).bindFromRequest.fold(
        formWithErrors => {
          Future.successful(BadRequest(""))
        },
        userData => {
          implicit val timeout = Timeout(1.minute)
          (userAggregateManager ? UserAggregateManager.UserEnvelope(request.identity.id, UserAggregate.StartInstance(
              clusterLookup(userData.cluster),
              imageLookup(userData.image)))).map {
            case InstanceAggregateManager.InstanceStarted(id) =>
              Ok(id)
          }
        }
    )
  }

  def terminateInstance(instanceId: String) = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userAggregateManager ? UserAggregateManager.UserEnvelope(request.identity.id, UserAggregate.TerminateInstance(instanceId))).map {
      case InstanceAggregate.Ack =>
        Accepted
      case UserAggregate.InstanceNotOwnedByUser =>
        Forbidden
    }
  }

  def login = silhouette.UnsecuredAction.async { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => Future.successful {
        // binding failure, you retrieve the form containing errors:
        BadRequest(views.html.login(noneIfProd(formWithErrors), socialProviders))
      },
      userData => {
        val loginInfo = LoginInfo("dummy", userData.identity)
        identityService.retrieve(loginInfo).flatMap {
          case Some(user) =>
            val result = redirectAfterLogin(request)
            silhouette.env.authenticatorService.create(loginInfo).flatMap { authenticator =>
              silhouette.env.eventBus.publish(LoginEvent(user, request))
              log.info(s"Logged in as $user")
              silhouette.env.authenticatorService.init(authenticator).flatMap { v =>
                silhouette.env.authenticatorService.embed(v, result)
              }
            }
          case None =>
            ??? // Shouldn't currently be possible
        }
      }
    )
  }

  def logout = Action { implicit request =>
    Redirect(routes.MainController.index).withSession()
  }

  def authenticate(provider: String) = Action.async { implicit request =>
    (socialProviders.get[SocialProvider](provider) match {
      case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
        p.authenticate().flatMap {
          case Left(result) => Future.successful(result)
          case Right(authInfo) => for {
            profile <- p.retrieveProfile(authInfo)
            user <- identityService.retrieve(profile.loginInfo).map(_.get)
            authenticator <- silhouette.env.authenticatorService.create(profile.loginInfo)
            value <- silhouette.env.authenticatorService.init(authenticator)
            result <- silhouette.env.authenticatorService.embed(value, redirectAfterLogin(request))
          } yield {
            log.info(s"Logged in as $user")
            silhouette.env.eventBus.publish(LoginEvent(user, request))
            result
          }
        }
      case _ => Future.failed(new ProviderException(s"Cannot authenticate with unexpected social provider $provider"))
    }).recover {
      case e: ProviderException =>
        log.error("Unexpected provider error", e)
        Redirect(routes.MainController.index())
    }
  }

  def instanceRegistration = Action.async { implicit request =>
    import InstanceAggregateManager.{InstanceEnvelope, VerifyJwt}
    implicit val timeout = Timeout(1.minute)
    val authHeaderRegex = "^Bearer (.*)$".r
    request.headers.get("Authorization")
      .collect { case authHeaderRegex(token) => token }
      .map { token =>
        (instanceAggregateManager ? VerifyJwt(token)).flatMap {
          case InstanceAggregate.ValidJwt(instanceId) =>
            log.debug(s"Valid JWT for $instanceId")
            request.body.asText match {
              case Some(uri) =>
                (instanceAggregateManager ? InstanceEnvelope(instanceId, InstanceAggregate.AssociateUri(uri))).map { _ =>
                  Ok("")
                }.recover {
                  case e => InternalServerError(e.getMessage)
                }
              case None =>
                Future.successful(BadRequest("No valid uri"))
            }
          case InstanceAggregate.InvalidJwt(msg) =>
            log.warn(s"Invalid JWT: $msg\n${request.body}")
            Future.successful(BadRequest(msg))
        }
      }
      .getOrElse(Future.successful(Forbidden("No valid JWT provided")))
  }

  def webjars(path: String, file: String) =
    Assets.versioned(path, fudgeFileLocation(file))

  private val fudgeFileLocation: String => String = {
    case s if s.startsWith("polymer") =>
      "github-com-Polymer-"+s
    case s if s.startsWith("promise-polyfill") =>
      "github-com-PolymerLabs-"+s
    case s if s.startsWith("app-") =>
      "github-com-PolymerElements-"+s
    case s if s.startsWith("font-") =>
      "github-com-PolymerElements-"+s
    case s if s.startsWith("iron-") =>
      "github-com-PolymerElements-"+s
    case s if s.startsWith("neon-") =>
      "github-com-PolymerElements-"+s
    case s if s.startsWith("paper-") =>
      "github-com-PolymerElements-"+s
    case s if s.startsWith("web-animations-js") =>
      "github-com-web-animations-"+s
    case s => s
  }

  val loginForm = Form(
    mapping(
        "identity" -> nonEmptyText
    )(LoginData.apply)(LoginData.unapply)
  )

  def newInstanceForm(userId: String) = Form(
    mapping(
        "image" -> nonEmptyText,
        "cluster" -> nonEmptyText
    )(NewInstanceRequest.apply)(NewInstanceRequest.unapply).verifying(r =>
        imageLookup.contains(r.image) && clusterLookup.contains(r.cluster)
    )
  )

  case class NewInstanceRequest(image: String, cluster: String)
  case class InstanceRegistrationRequest(uri: String)

  private val imageLookup = Map(
    "Base (Alpine)" -> "docker://dit4c/dit4c-container-base:alpine",
    "IPython"      -> "docker://dit4c/dit4c-container-ipython",
    "OpenRefine"   -> "docker://dit4c/dit4c-container-openrefine",
    "NLTK"         -> "docker://dit4c/dit4c-container-nltk",
    "RStudio"      -> "docker://dit4c/dit4c-container-rstudio"
  )

  private val clusterLookup = Map(
    "Default" -> "default"
  )

  implicit private val readsInstanceRegistration: Reads[InstanceRegistrationRequest] =
    (__ \ 'uri).read[String].map(InstanceRegistrationRequest(_))

  private def noneIfProd[A](v: A): Option[A] = environment.mode match {
    case Mode.Prod => None
    case _ => Some(v)
  }

  private def redirectAfterLogin[A](request: Request[A]): Result =
    request.session.get("redirect_uri") match {
      case Some(uri) => Redirect(uri).withSession(request.session - "redirect_uri")
      case None => Redirect(routes.MainController.index)
    }

}

class GetInstancesActor(out: ActorRef,
    user: IdentityService.User,
    userAggregateManager: ActorRef @@ UserAggregateManager,
    instanceAggregateManager: ActorRef @@ InstanceAggregateManager)
    extends Actor
    with ActorLogging {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  var pollFunc: Option[Cancellable] = None

  override def preStart = {
    import context.dispatcher
    import akka.pattern.pipe
    implicit val timeout = Timeout(1.minute)
    pollFunc = Some(context.system.scheduler.schedule(Duration.Zero, 5.seconds) {
      (userAggregateManager ? UserAggregateManager.UserEnvelope(user.id, UserAggregate.GetAllInstanceIds)).foreach {
        case UserAggregate.UserInstances(instanceIds) =>
          instanceIds.toSeq.foreach { id =>
            (instanceAggregateManager ? InstanceAggregateManager.InstanceEnvelope(id, InstanceAggregate.GetStatus))
              .collect { case msg: InstanceAggregate.RemoteStatus =>
                val url: Option[String] =
                  msg.uri.map(Uri(_).withPath(Uri.Path./).toString)
                InstanceResponse(id, effectiveState(msg.state, url), url)
              }
              .recover { case _ =>
                InstanceResponse(id, "Unknown", None)
              }
              .pipeTo(self)
          }
      }
    })
  }

  override def postStop = {
    pollFunc.foreach(_.cancel)
  }

  override def receive = {
    case r: InstanceResponse =>
      out ! TextMessage(Json.asciiStringify(Json.toJson(r)))
    case unknown =>
      log.error(s"Unhandled message: $unknown")
      context.stop(self)
  }

  case class InstanceResponse(id: String, state: String, url: Option[String])

  implicit private val writesInstanceResponse: Writes[InstanceResponse] = (
      (__ \ 'id).write[String] and
      (__ \ 'state).write[String] and
      (__ \ 'url).writeNullable[String]
    )(unlift(InstanceResponse.unapply))

  private def effectiveState(state: String, url: Option[String]) = state match {
    case "CREATED" => "Waiting For Image"
    case "PRESTART" => "Starting"
    case "STARTED" if url.isDefined  => "Available"
    case "STARTED" if url.isEmpty    => "Started"
    case "EXITED" => "Stopped"
    case "ERRORED" => "Errored"
    case _ => state
  }

}

case class LoginData(identity: String)
