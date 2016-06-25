package controllers

import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.i18n._
import com.softwaremill.tagging._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import akka.actor.ActorRef
import services._
import domain._
import akka.util.Timeout
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.StatusCodes.ServerError

class MainController(
    val messagesApi: MessagesApi,
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager,
    val userAggregateManager: ActorRef @@ UserAggregateManager)
    extends Controller
    with I18nSupport {

  import play.api.libs.concurrent.Execution.Implicits._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  val log = play.Logger.underlying

  def index = UserAction { request =>
    request.userId match {
      case Some(userId) =>
        Ok(views.html.index(imageLookup.keys.toList.sorted))
      case None =>
        Ok(views.html.login(loginForm))
    }
  }

  def getInstances = (UserAction andThen UserRequired).async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userAggregateManager ? UserAggregateManager.UserEnvelope(request.userId.get, UserAggregate.GetAllInstanceIds)).flatMap {
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
                InstanceResponse(id, remoteState.state)
              case (id, None) =>
                InstanceResponse(id, "Unknown")
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

  def login = Action { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => {
        // binding failure, you retrieve the form containing errors:
        BadRequest(views.html.login(formWithErrors))
      },
      userData => {
        Redirect(routes.MainController.index)
          .withSession("user-id" -> userData.userId)
      }
    )
  }

  def logout = Action { implicit request =>
    Redirect(routes.MainController.index).withSession()
  }

  def instanceRegistration = Action(parse.json) { implicit request =>
    println(request)
    println(request.body)
    Ok("")
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
        "user-id" -> nonEmptyText
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
  case class InstancesResponse(instances: Seq[InstanceResponse])
  case class InstanceResponse(id: String, state: String)

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

  implicit private val writesInstanceResponse: Writes[InstanceResponse] = (
      (__ \ 'id).write[String] and
      (__ \ 'state).write[String]
    )(unlift(InstanceResponse.unapply))

  implicit private val writesInstancesResponse: Writes[InstancesResponse] = (
      (__ \ 'instances).write[Seq[InstanceResponse]]
    ).contramap({ case InstancesResponse(instances) => instances })


}
