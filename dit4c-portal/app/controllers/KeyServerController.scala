package controllers

import com.softwaremill.tagging._
import akka.actor.ActorRef
import utils.auth.DefaultEnv
import services.InstanceAggregateManager
import scala.concurrent.ExecutionContext
import play.api.mvc.Controller
import scala.concurrent.Future
import domain.InstanceAggregate
import java.security.PublicKey
import akka.util.Timeout
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.Action

class KeyServerController(
    val instanceAggregateManager: ActorRef @@ InstanceAggregateManager)(implicit ec: ExecutionContext)
    extends Controller {

  import akka.pattern.ask
  implicit val timeout = Timeout(1.minute)

  def serviceKeys(serviceName: String) = Action.async { implicit request =>
    for {
      jwkSet <- serviceName.split('-').toList match {
        case "instance" :: instanceId :: Nil =>
          instanceJwkFromId(instanceId).map(v => JwkSet(v.toSeq))
        case _ =>
          Future.successful(JwkSet())
      }
    } yield Ok(Json.toJson(jwkSet))
  }

  def serviceKey(serviceName: String, kid: String) = Action.async { implicit request =>
    val fJwk = serviceName.split('-').toList match {
      case "instance" :: instanceId :: Nil =>
        instanceJwkFromId(instanceId)
      case _ =>
        Future.successful(None)
    }
    fJwk.map {
      case Some(jwk) if jwk.validate((__ \ 'kid).read[String]).asOpt == Some(kid) => Ok(jwk)
      case _ => NotFound
    }
  }


  def instanceJwkFromId(id: String): Future[Option[JsObject]] =
    (instanceAggregateManager ? InstanceAggregateManager.InstanceEnvelope(id, InstanceAggregate.GetJwk)).collect {
      case InstanceAggregate.InstanceJwk(jwk) => Some(jwk)
      case InstanceAggregate.NoJwkExists => None
    }

  case class JwkSet(keys: Seq[JsObject] = Nil)

  implicit val writesKeySet: Writes[JwkSet] = (__ \ 'key).write[Seq[JsObject]].contramap(_.keys)


}