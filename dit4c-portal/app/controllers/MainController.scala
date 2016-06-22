package controllers

import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.i18n._
import scala.concurrent.Future

class MainController(val messagesApi: MessagesApi) extends Controller
    with I18nSupport {

  def index = UserAction { request =>
    request.userId match {
      case Some(userId) =>
        Ok(views.html.index(imageLookup.keys.toList.sorted))
      case None =>
        Ok(views.html.login(loginForm))
    }
  }

  def newInstance = (UserAction andThen UserRequired).async { implicit request =>
    newInstanceForm(request.userId.get).bindFromRequest.fold(
        formWithErrors => {
          Future.successful(BadRequest(""))
        },
        userData => {
          ???
        }
    )
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

  object UserRequired extends ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]) = Future.successful {
      if (input.userId.isEmpty)
        Some(Forbidden)
      else
        None
    }
  }

  private val imageLookup = Map(
    "IPython"      -> "docker://dit4c/dit4c-container-ipython",
    "OpenRefine"   -> "docker://dit4c/dit4c-container-openrefine",
    "NLTK"         -> "docker://dit4c/dit4c-container-nltk",
    "RStudio"      -> "docker://dit4c/dit4c-container-rstudio"
  )

  private val clusterLookup = Map(
    "Default" -> "default"
  )



}
