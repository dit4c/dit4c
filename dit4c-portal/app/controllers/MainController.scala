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

class MainController(
    val messagesApi: MessagesApi,
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager,
    val userAggregateManager: ActorRef @@ UserAggregateManager,
    val silhouette: Silhouette[DefaultEnv],
    val identityService: IdentityService,
    val socialProviders: SocialProviderRegistry)
    extends Controller
    with I18nSupport {

  import play.api.libs.concurrent.Execution.Implicits._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  val log = play.Logger.underlying

  def index = silhouette.UserAwareAction { request =>
    request.identity match {
      case Some(IdentityService.User(_, _)) =>
        Ok(views.html.index(imageLookup.keys.toList.sorted))
      case None =>
        Ok(views.html.login(Some(loginForm), socialProviders))
    }
  }

  def getInstances = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userAggregateManager ? UserAggregateManager.UserEnvelope(request.identity.id, UserAggregate.GetAllInstanceIds)).flatMap {
      case UserAggregate.UserInstances(instanceIds) =>
        val idSeq = instanceIds.toSeq
        Future.sequence(idSeq.map { instanceId =>
          (instanceAggregateManager ? InstanceAggregateManager.InstanceEnvelope(instanceId, InstanceAggregate.GetStatus))
            .collect { case msg: InstanceAggregate.RemoteStatus => Some(msg) }
            .recover { case _ => None }
        }).map { instances =>
          val instancesResponse = InstancesResponse(
            idSeq.zip(instances).map {
              case (id, Some(remoteState)) =>
                val url: Option[String] =
                  remoteState.uri.map(Uri(_).withPath(Uri.Path./).toString)
                InstanceResponse(id, remoteState.state, url)
              case (id, None) =>
                InstanceResponse(id, "Unknown", None)
            })
          Ok(Json.toJson(instancesResponse))
        }.recover {
          case e =>
            log.error("Get instances failed", e)
            InternalServerError
        }
    }
  }

  def newInstance = (UserAction andThen UserRequired).async { implicit request =>
    newInstanceForm(request.userId.get).bindFromRequest.fold(
        formWithErrors => {
          Future.successful(BadRequest(""))
        },
        userData => {
          implicit val timeout = Timeout(1.minute)
          (userAggregateManager ? UserAggregateManager.UserEnvelope(request.userId.get, UserAggregate.StartInstance(
              clusterLookup(userData.cluster),
              imageLookup(userData.image),
              routes.MainController.instanceRegistration.absoluteURL()))).map {
            case InstanceAggregateManager.InstanceStarted(id) =>
              Ok(id)
            case ClusterAggregate.UnableToStartInstance =>
              ServiceUnavailable
          }
        }
    )
  }

  def terminateInstance(instanceId: String) = (UserAction andThen UserRequired).async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userAggregateManager ? UserAggregateManager.UserEnvelope(request.userId.get, UserAggregate.TerminateInstance(instanceId))).map {
      case ClusterAggregate.InstanceTerminating(id) =>
        Ok(id)
      case UserAggregate.InstanceNotOwnedByUser =>
        Forbidden
    }
  }

  def login = silhouette.UnsecuredAction.async { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => Future.successful {
        // binding failure, you retrieve the form containing errors:
        BadRequest(views.html.login(Some(formWithErrors), socialProviders))
      },
      userData => {
        val loginInfo = LoginInfo("dummy", userData.identity)
        identityService.retrieve(loginInfo).flatMap {
          case Some(user) =>
            val result = Redirect(routes.MainController.index)
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
            result <- silhouette.env.authenticatorService.embed(value, Redirect(routes.MainController.index()))
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

  def instanceRegistration = Action.async(parse.json) { implicit request =>
    import InstanceAggregateManager.{InstanceEnvelope, VerifyJwt}
    implicit val timeout = Timeout(1.minute)
    val authHeaderRegex = "^Bearer (.*)$".r
    request.headers.get("Authorization")
      .collect { case authHeaderRegex(token) => token }
      .map { token =>
        (instanceAggregateManager ? VerifyJwt(token)).flatMap {
          case InstanceAggregate.ValidJwt(instanceId) =>
            log.debug(s"Valid JWT for $instanceId")
            log.debug(Json.prettyPrint(request.body))
            Json.fromJson[InstanceRegistrationRequest](request.body).asOpt match {
              case Some(InstanceRegistrationRequest(uri)) =>
                (instanceAggregateManager ? InstanceEnvelope(instanceId, InstanceAggregate.AssociateUri(uri))).map { _ =>
                  Ok("")
                }.recover {
                  case e => InternalServerError(e.getMessage)
                }
              case None =>
                Future.successful(BadRequest("No valid uri"))
            }
          case InstanceAggregate.InvalidJwt(msg) =>
            println(s"Invalid JWT: $msg")
            println(request.body)
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
  case class InstancesResponse(instances: Seq[InstanceResponse])
  case class InstanceResponse(id: String, state: String, url: Option[String])

  object UserRequired extends ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]) = Future.successful {
      if (input.userId.isEmpty)
        Some(Forbidden)
      else
        None
    }
  }

  private val imageLookup = Map(
    "gotty"        -> "docker://dit4c/gotty",
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

  implicit private val writesInstanceResponse: Writes[InstanceResponse] = (
      (__ \ 'id).write[String] and
      (__ \ 'state).write[String] and
      (__ \ 'url).writeNullable[String]
    )(unlift(InstanceResponse.unapply))

  implicit private val writesInstancesResponse: Writes[InstancesResponse] = (
      (__ \ 'instances).write[Seq[InstanceResponse]]
    ).contramap({ case InstancesResponse(instances) => instances })


}
