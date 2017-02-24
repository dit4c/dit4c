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
import play.api.libs.ws.WSClient
import play.api.libs.ws.StreamedResponse
import utils.oauth.AuthorizationCodeGenerator
import java.time.Instant
import scala.util.Failure
import scala.util.Success
import play.api.http.websocket.CloseMessage
import play.twirl.api.Html
import org.bouncycastle.openpgp.PGPSignature
import akka.http.scaladsl.Http

class MainController(
    val environment: Environment,
    val messagesApi: MessagesApi,
    val trackingScripts: TrackingScripts,
    val instanceSharder: ActorRef @@ InstanceSharder.type,
    val schedulerSharder: ActorRef @@ SchedulerSharder.type,
    val userSharder: ActorRef @@ UserSharder.type,
    val silhouette: Silhouette[DefaultEnv],
    val identityService: IdentityService,
    val oauthDataHandler: InstanceOAuthDataHandler,
    val socialProviders: SocialProviderRegistry,
    val authorizationCodeGenerator: AuthorizationCodeGenerator,
    val publicImages: Seq[PublicImage])(implicit system: ActorSystem, materializer: Materializer)
    extends Controller
    with I18nSupport {

  import play.api.libs.concurrent.Execution.Implicits._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  val httpClient = Http()(system)
  val log = play.api.Logger(this.getClass)

  def index = silhouette.UserAwareAction { implicit request =>
    request.identity match {
      case Some(IdentityService.User(_, _)) =>
        Ok(views.html.index(imageLookup.keys.toList.sorted, trackingScripts.html))
      case None =>
        Ok(views.html.login(noneIfProd(loginForm), socialProviders, trackingScripts.html))
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
          Props(classOf[GetInstancesActor], out, user, userSharder, instanceSharder)
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
          val (schedulerId, clusterId) = resolveSchedulerAndCluster(
              userData.cluster)
          val uaOp = imageLookup.get(userData.image) match {
            case Some(image) =>
              UserAggregate.StartInstance(schedulerId, clusterId, image)
            case None =>
              UserAggregate.StartInstanceFromInstance(
                  schedulerId, clusterId, userData.image)
          }
          (userSharder ? UserSharder.Envelope(request.identity.id, uaOp)).map {
            case InstanceAggregate.Started(id) =>
              log.info(s"User ${request.identity.id} created instance ${id}")
              Ok(id)
            case InstanceAggregate.NoImageExists => NotFound
          }
        }
    )
  }

  def saveInstance(instanceId: String) = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userSharder ? UserSharder.Envelope(request.identity.id, UserAggregate.SaveInstance(instanceId))).map {
      case SchedulerAggregate.Ack =>
        log.info(s"User ${request.identity.id} requested to save instance ${instanceId}")
        Accepted
      case UserAggregate.InstanceNotOwnedByUser =>
        Forbidden
      case SchedulerAggregate.UnableToSendMessage =>
        InternalServerError
    }
  }

  def discardInstance(instanceId: String) = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userSharder ? UserSharder.Envelope(request.identity.id, UserAggregate.DiscardInstance(instanceId))).map {
      case SchedulerAggregate.Ack =>
        log.info(s"User ${request.identity.id} requested to discard instance ${instanceId}")
        Accepted
      case UserAggregate.InstanceNotOwnedByUser =>
        Forbidden
      case SchedulerAggregate.UnableToSendMessage =>
        InternalServerError
    }
  }

  def exportImage(instanceId: String) = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userSharder ? UserSharder.Envelope(request.identity.id, UserAggregate.GetInstanceImageUrl(instanceId, "portal"))).flatMap {
      case InstanceAggregate.InstanceImage(url) =>
        import akka.http.scaladsl.model._
        val uri = Uri(url)
        val req = uri.authority.userinfo match {
          case "" => HttpRequest(uri = uri)
          case userinfo =>
            import akka.http.scaladsl.model.headers._
            import java.util.Base64
            val token = Base64.getEncoder().encodeToString(userinfo.getBytes("utf8"))
            HttpRequest(uri = uri).addCredentials(GenericHttpCredentials("Basic", token))
        }
        httpClient.singleRequest(req).map {
          case HttpResponse(StatusCodes.OK, headers, entity, _) =>
            val filename = Uri(url).path.reverse.head
            val headersToOmit = "Content-Type" :: "Content-Length" :: Nil
            val responseHeaders: Seq[(String,String)] = headers.flatMap {
              case h if headersToOmit.contains(h.name) => Nil
              case h => List((h.name, h.value()))
            } :+ ("Content-Disposition" -> s"""attachment; filename="$filename"""")
            log.info(s"User ${request.identity.id} is exporting instance ${instanceId}")
            Ok.chunked(entity.withoutSizeLimit.dataBytes).withHeaders(responseHeaders: _*)
          case HttpResponse(status, headers, _, _) =>
            log.error(s"Unable to fetch image for $instanceId: $status $headers")
            InternalServerError
        }
      case InstanceAggregate.NoImageExists =>
        Future.successful(NotFound)
      case UserAggregate.InstanceNotOwnedByUser =>
        Future.successful(Forbidden)
    }
  }

  def getAvailableClusters = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    val queryMsg = UserSharder.Envelope(
        request.identity.id, UserAggregate.GetAvailableClusters)
    (userSharder ? queryMsg).flatMap {
      case UserAggregate.AvailableClusters(clusters) =>
        for {
          // Get friendly names for the schedulers
          schedulerNames <- schedulerNames(clusters.map(_.schedulerId).toSet)
          // Get info (including names) for the clusters
          clusterInfo <- clusterInfo(clusters.map(v => (v.schedulerId, v.clusterId)).toSet)
        } yield {
          val clusterOptions = ClusterOptions(clusters.flatMap { c =>
            val joinedId = s"${c.schedulerId}.${c.clusterId}"
            val schedulerName =
              schedulerNames.get(c.schedulerId)
                .getOrElse(c.schedulerId.takeRight(8))
            clusterInfo.get((c.schedulerId, c.clusterId)) match {
              case Some(Cluster.Active(clusterName, supportsSave)) =>
                val display = s"${schedulerName} - ${clusterName}" +
                  c.until.map(t => s" (until $t)").getOrElse("") +
                  (if (supportsSave) "" else " (no saving)")
                Some(ClusterOption(joinedId, display))
              case _ =>
                None
            }
          })
          Ok(Json.toJson(clusterOptions))
        }
    }
  }

  def createSharingLink(instanceId: String) = silhouette.SecuredAction.async { implicit request =>
    implicit val timeout = Timeout(1.minute)
    (userSharder ? UserSharder.Envelope(request.identity.id, UserAggregate.GetInstanceImageUrl(instanceId, "portal"))).flatMap {
      case InstanceAggregate.InstanceImage(_) =>
        val payload = InstanceSharingAuthorization(request.identity.id, instanceId)
        // Good - it's a preserved instance, so we can go ahead
        val lifetime = 6.hours
        val expires = Instant.now.plusSeconds(lifetime.toSeconds)
        val token = authorizationCodeGenerator.create(payload, lifetime)
        val url = routes.MainController.redeemSharingLink(token).absoluteURL()
        Future.successful(Ok(Json.toJson(InstanceSharingLink(url, expires))))
      case InstanceAggregate.NoImageExists =>
        Future.successful(NotFound)
      case UserAggregate.InstanceNotOwnedByUser =>
        Future.successful(Forbidden)
    }
  }

  def redeemSharingLink(token: String) = silhouette.UserAwareAction.async { implicit request =>
    request.identity match {
      case Some(user: IdentityService.User) =>
        authorizationCodeGenerator.decode[InstanceSharingAuthorization](token) match {
          case Success(a) =>
            implicit val timeout = Timeout(1.minute)
            val msg = UserAggregate.ReceiveSharedInstance(a.userId, a.instanceId)
            (userSharder ? UserSharder.Envelope(user.id, msg)).map {
              case UserAggregate.InstanceReceived =>
                Redirect(routes.MainController.index())
            }
          case Failure(exception) =>
            Future.successful(Forbidden(exception.getMessage))
        }
      case None =>
        Future.successful {
          Redirect(routes.MainController.index)
            .withSession("redirect_uri" -> routes.MainController.redeemSharingLink(token).absoluteURL())
        }
    }
  }

  def login = silhouette.UnsecuredAction.async { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => Future.successful {
        // binding failure, you retrieve the form containing errors:
        BadRequest(views.html.login(noneIfProd(formWithErrors), socialProviders, trackingScripts.html))
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
    case s if s.startsWith("marked-") =>
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

  def resolveSchedulerAndCluster(
      joinedSchedulerClusterId: String): (String, String) = {
    val jsciRegex = """^(.*)\.(.*)$""".r
    joinedSchedulerClusterId match {
      case jsciRegex(schedulerId, clusterId) =>
        (schedulerId, clusterId)
    }
  }

  def newInstanceForm(userId: String) = Form(
    mapping(
        "image" -> nonEmptyText,
        "cluster" -> nonEmptyText
    )(NewInstanceRequest.apply)(NewInstanceRequest.unapply)
  )

  case class NewInstanceRequest(image: String, cluster: String)
  case class InstanceRegistrationRequest(uri: String)
  case class InstanceSharingAuthorization(userId: String, instanceId: String)
  case class InstanceSharingLink(url: String, expires: Instant)
  case class ClusterOption(joinedId: String, displayName: String)
  case class ClusterOptions(options: List[ClusterOption])

  implicit val formatInstanceSharingAuthorization: OFormat[InstanceSharingAuthorization] = (
      (__ \ 'user).format[String] and
      (__ \ 'instance).format[String]
  )(InstanceSharingAuthorization.apply, unlift(InstanceSharingAuthorization.unapply))

  implicit val writesInstanceSharingLink: OWrites[InstanceSharingLink] = (
      (__ \ 'url).write[String] and
      (__ \ 'expires).write[Instant]
  )((isl: InstanceSharingLink) => (isl.url, isl.expires))

  implicit val writesClusterOption: OWrites[ClusterOption] = (
      (__ \ 'id).write[String] and
      (__ \ 'display).write[String]
  )(unlift(ClusterOption.unapply))

  implicit val writesClusterOptions: OWrites[ClusterOptions] =
      (__ \ 'clusters).write[List[ClusterOption]]
        .contramap(_.options)

  private lazy val imageLookup: Map[String, String] = publicImages.map(PublicImage.unapply).flatten.toMap

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

  protected def schedulerNames(schedulerIds: Set[String]): Future[Map[String, String]] =
    schedulerIds
      .map { schedulerId =>
        implicit val timeout = Timeout(10.seconds)
        // Extract the user ID from the OpenPGP key
        import dit4c.common.KeyHelpers._
        (schedulerSharder ? SchedulerSharder.Envelope(schedulerId, SchedulerAggregate.GetKeys))
          .collect {
            case SchedulerAggregate.CurrentKeys(kb, _) =>
              import scala.collection.JavaConversions._
              val kr = parseArmoredPublicKeyRing(kb).right.get
              val k = kr.getPublicKey
              // This is wrong, because it doesn't check for revocations, but
              // it will do for now.
              val userId = k.getUserIDs.toSeq.head.toString
              val schedulerName = userId.split("<(".toCharArray).head
              Map(schedulerId -> schedulerName)
          }
          .recover { case e => Map.empty[String, String] }
      }
      // Collapse to single future
      .reduceOption { (aF, bF) =>
        for (a <- aF; b <- bF) yield a ++ b
      }
      .getOrElse(Future.successful(Map.empty))

  protected def clusterInfo(
      ids: Set[(String, String)]): Future[Map[(String, String), Cluster.ClusterInfo]] =
    ids
      .map { case (schedulerId, clusterId) =>
        implicit val timeout = Timeout(10.seconds)
        // Extract the user ID from the OpenPGP key
        import dit4c.common.KeyHelpers._
        (schedulerSharder ? SchedulerSharder.Envelope(schedulerId,
            SchedulerAggregate.ClusterEnvelope(clusterId, Cluster.GetInfo)))
          .collect {
            case Cluster.CurrentInfo(info, _) =>
              Map((schedulerId, clusterId) -> info)
          }
          .recover { case e => Map.empty[(String, String), Cluster.ClusterInfo] }
      }
      // Collapse to single future
      .reduceOption { (aF, bF) =>
        for (a <- aF; b <- bF) yield a ++ b
      }
      .getOrElse(Future.successful(Map.empty))
}

class GetInstancesActor(out: ActorRef,
    user: IdentityService.User,
    userSharder: ActorRef @@ UserSharder.type,
    instanceSharder: ActorRef @@ InstanceSharder.type)
    extends Actor
    with ActorLogging {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  var pollFunc: Option[Cancellable] = None

  override def preStart = {
    import context.dispatcher
    import akka.pattern.pipe
    implicit val timeout = Timeout(5.seconds)
    pollFunc = Some(context.system.scheduler.schedule(1.micro, 5.seconds) {
      (userSharder ? UserSharder.Envelope(user.id, UserAggregate.GetAllInstanceIds)).foreach {
        case UserAggregate.UserInstances(instanceIds) =>
          instanceIds.toSeq.foreach { id =>
            (instanceSharder ? InstanceSharder.Envelope(id, InstanceAggregate.GetStatus))
              .collect { case msg: InstanceAggregate.CurrentStatus =>
                val url: Option[String] =
                  msg.uri.map(Uri(_).withPath(Uri.Path./).toString)
                InstanceResponse(
                    id,
                    effectiveState(msg.state, url),
                    msg.description,
                    url,
                    msg.timestamps,
                    msg.availableActions.toSeq.sortBy(_.toString))
              }
              .recover { case e =>
                log.error(e, s"Failed to get instance status for $id")
                InstanceResponse(id, "Unknown", "No status received", None, InstanceAggregate.EventTimestamps(), Nil)
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
    case _: CloseMessage =>
      context.stop(self)
    case unknown =>
      log.error(s"Unhandled message: $unknown")
      context.stop(self)
  }

  case class InstanceResponse(
      id: String,
      state: String,
      info: String,
      url: Option[String],
      timestamps: InstanceAggregate.EventTimestamps,
      availableActions: Seq[InstanceAggregate.InstanceAction])

  implicit private val writesEventTimestamps: Writes[InstanceAggregate.EventTimestamps] = (
      (__ \ 'created).writeNullable[Instant] and
      (__ \ 'completed).writeNullable[Instant]
    )(unlift(InstanceAggregate.EventTimestamps.unapply))

  implicit private val writesInstanceAction: Writes[InstanceAggregate.InstanceAction] =
    Writes { action => JsString(action.toString.toLowerCase) }

  implicit private val writesInstanceResponse: Writes[InstanceResponse] = (
      (__ \ 'id).write[String] and
      (__ \ 'state).write[String] and
      (__ \ 'info).write[String] and
      (__ \ 'url).writeNullable[String] and
      (__ \ 'timestamps).write[InstanceAggregate.EventTimestamps] and
      (__ \ 'actions).write[Seq[InstanceAggregate.InstanceAction]]
    )(unlift(InstanceResponse.unapply))

  private def effectiveState(state: String, url: Option[String]) = state match {
    case "CREATED" => "Waiting For Image"
    case "STARTED" if url.isDefined  => "Available"
    case "STARTED" if url.isEmpty    => "Started"
    case state => state.toUpperCase.head +: state.toLowerCase.tail
  }

}

case class LoginData(identity: String)

case class TrackingScripts(html: Html)
