package services

import scala.concurrent.duration._

import org.scalacheck.Gen
import org.specs2.ScalaCheck
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification

import com.softwaremill.tagging._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.testkit.TestProbe
import domain.InstanceAggregate
import pdi.jwt.JwtClaim
import pdi.jwt.JwtJson
import scalaoauth2.provider.AuthorizationRequest
import utils.oauth.AuthorizationCodeGenerator
import com.mohiva.play.silhouette.api.LoginInfo
import scalaoauth2.provider.AuthInfo
import domain.IdentityAggregate
import testing.ScalaCheckHelpers
import org.scalacheck.Arbitrary
import scala.concurrent.Await
import com.typesafe.config.ConfigFactory

class InstanceOAuthDataHandlerSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck with ScalaCheckHelpers {

  import LoginInfo.jsonFormat

  val acg = new AuthorizationCodeGenerator("notmuchofasecret")

  "InstanceOAuthDataHandler" >> {

    "validateClient" >> {

      "true if instance with valid JWT secret" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(actorSystem("dh-vc-instance"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$instanceId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        // Response can only complete immediately if the token is incorrectly formed
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token)) =>
              probe.reply(InstanceAggregate.ValidJwt(instanceId))
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beTrue.await
        }
      }).setGen(Gen.identifier)

      "false if instance JWT verification fails" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(actorSystem("dh-vc-instance"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$instanceId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token)) =>
              probe.reply(InstanceAggregate.InvalidJwt)
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beFalse.await
        }
      }).setGen(Gen.identifier)

      "false if client_id doesn't match JWT" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(actorSystem("dh-vc-instance"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        val otherId = "notthesameissuer"
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$otherId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceSharder.Envelope(instanceId, InstanceAggregate.VerifyJwt(token)) =>
              probe.reply(InstanceAggregate.ValidJwt(otherId))
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beFalse.await
        }
      }).setGen(Gen.identifier)

      "always false without credentials" >> {
        val probe = TestProbe()(actorSystem("dh-vc-nocreds"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        (dh.validateClient(new AuthorizationRequest(Map.empty, Map.empty)) must beFalse.await) and
        (probe.msgAvailable must beFalse)
      }

      "always false if not an instance" >> {
        val probe = TestProbe()(actorSystem("dh-vc-notinstance"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        val params = Map(
            "client_id" -> Seq("not_an_instance"),
            "client_secret" -> Seq("whatever"))
        (dh.validateClient(new AuthorizationRequest(Map.empty, params)) must beFalse.await) and
        (probe.msgAvailable must beFalse)
      }

    }

    "findAuthInfoByCode" >> {

      "extracts info from code and then resolves user identity" >> prop({ (instanceId: String, info: AuthInfo[IdentityService.User]) =>
        val probe = TestProbe()(actorSystem("dh-vc-instance"))
        val dh = new InstanceOAuthDataHandler(acg,
            probe.ref.taggedWith[InstanceSharder.type],
            new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))
        import LoginInfo.jsonFormat
        import InstanceOAuthDataHandler.authInfoFormat
        val code = Await.result(dh.createAuthCode(info), 1.second)
        val fResponse = dh.findAuthInfoByCode(code)
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case IdentitySharder.Envelope(key, IdentityAggregate.GetUser) if key == IdentityService.toIdentityKey(info.user.loginInfo) =>
              probe.reply(IdentityAggregate.UserFound(info.user.id))
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beSome(info).await
        }
      })

    }

    "findUser" >> {

      // Not needed for Authorization Code Grant, so...
      "not implemented - always returns None" >> {
        val probe = TestProbe()(actorSystem("dh-fu-always"))
        val dh = stubbedDataHandler(probe)
        dh.findUser(new AuthorizationRequest(Map.empty, Map.empty)) must beNone.await
      }

    }

  }

  def stubbedDataHandler(probe: TestProbe) =
    new InstanceOAuthDataHandler(acg,
        probe.ref.taggedWith[InstanceSharder.type],
        new IdentityService(probe.ref.taggedWith[IdentitySharder.type]))

  def actorSystem(name: String) = ActorSystem(name, ConfigFactory.load(actorSystemConfig))

  private lazy val actorSystemConfig = ConfigFactory.parseString("""
    |akka.actor.provider = "local"
    |akka.remote = {}
    |akka.cluster = {}
    |""".stripMargin)

}