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

class InstanceOAuthDataHandlerSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck {

  "InstanceOAuthDataHandler" >> {

    "validateClient" >> {

      "true if instance with valid JWT secret" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(ActorSystem("dh-vc-instance"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$instanceId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceAggregateManager.VerifyJwt(token) =>
              probe.reply(InstanceAggregate.ValidJwt(instanceId))
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beTrue.await
        }
      }).setGen(Gen.identifier)

      "false if instance JWT verification fails" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(ActorSystem("dh-vc-instance"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$instanceId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceAggregateManager.VerifyJwt(token) =>
              probe.reply(InstanceAggregate.InvalidJwt)
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beFalse.await
        }
      }).setGen(Gen.identifier)

      "false if client_id doesn't match JWT" >> prop({ (instanceId: String) =>
        val probe = TestProbe()(ActorSystem("dh-vc-instance"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        val otherId = "notthesameissuer"
        val params = Map(
            "client_id" -> Seq(s"instance-$instanceId"),
            "client_secret" -> Seq(JwtJson.encode(JwtClaim("", Some(s"instance-$otherId")))))
        val fResponse = dh.validateClient(new AuthorizationRequest(Map.empty, params))
        (fResponse.isCompleted must beFalse) and {
          probe.receiveOne(10.seconds) match {
            case InstanceAggregateManager.VerifyJwt(token) =>
              probe.reply(InstanceAggregate.ValidJwt(otherId))
            case msg =>
              failure(s"Unknown message: $msg")
          }
          fResponse must beFalse.await
        }
      }).setGen(Gen.identifier)

      "always false without credentials" >> {
        val probe = TestProbe()(ActorSystem("dh-vc-nocreds"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        (dh.validateClient(new AuthorizationRequest(Map.empty, Map.empty)) must beFalse.await) and
        (probe.msgAvailable must beFalse)
      }

      "always false if not an instance" >> {
        val probe = TestProbe()(ActorSystem("dh-vc-notinstance"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        val params = Map(
            "client_id" -> Seq("not_an_instance"),
            "client_secret" -> Seq("whatever"))
        (dh.validateClient(new AuthorizationRequest(Map.empty, params)) must beFalse.await) and
        (probe.msgAvailable must beFalse)
      }

    }

    "findUser" >> {

      // Not needed for Authorization Code Grant, so...
      "always returns None" >> {
        val probe = TestProbe()(ActorSystem("dh-fu-always"))
        val iam: ActorRef @@ InstanceAggregateManager = probe.ref.taggedWith[InstanceAggregateManager]
        val dh = new InstanceOAuthDataHandler(iam)
        dh.findUser(new AuthorizationRequest(Map.empty, Map.empty)) must beNone.await
      }

    }

  }

}