package controllers

import java.util.UUID
import scala.concurrent._
import scala.concurrent.duration._
import org.junit.runner.RunWith
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.PlaySpecification
import providers.db.CouchDB
import providers.db.EphemeralCouchDBInstance
import org.specs2.runner.JUnitRunner
import models._
import providers.auth.Identity
import play.api.test.WithApplication
import play.api.Play
import providers.InjectorPlugin
import utils.SpecUtils
import providers.RoutingMapEmitter
import play.api.libs.iteratee._
import play.api.mvc.Results
import akka.agent.Agent
import play.api.libs.concurrent.Promise
import play.api.http.HttpEntity
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import play.api.libs.streams.Streams
import akka.stream.scaladsl.Sink
import akka.util.ByteString

@RunWith(classOf[JUnitRunner])
class RoutingMapControllerSpec extends PlaySpecification with SpecUtils {
  
  val testImage = "dit4c/dit4c-container-ipython"

  "RoutingMapController" should {

    "have an event feed" in new WithApplication(fakeApp) {
      val controller = app.injector.instanceOf(classOf[RoutingMapController])
      val response: Future[Result] = controller.feed(FakeRequest())
      status(response) must_== 200
      contentType(response) must beSome("text/event-stream")

      val events = collectionAgent(contentAsJsonEnumerator(response))
      await(when(events)(!_.get.isEmpty))
      events.get must haveSize(1)
      val json = events.get.head
      (json \ "op").as[String] must_== "replace-all-routes"
      (json \ "routes").as[Seq[JsObject]] must beEmpty

      val session = new UserSession(db(app))
      val computeNodeDao = app.injector.instanceOf(classOf[ComputeNodeDAO])
      val containerDao = app.injector.instanceOf(classOf[ContainerDAO])
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val containers = Seq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )

      await(when(events)(_.get.size >= 4))
      val newEvents = events.get.drop(1).take(3)
      newEvents must allOf(beLike[JsValue] { case v: JsValue =>
        ((v \ "op").as[String] must_== "set-route") and
        ((v \ "route" \ "domain").as[String] must contain(".")) and
        ((v \ "route" \ "name").as[String] must not(beEmpty)) and
        ((v \ "route" \ "headers").as[Map[String,String]] must haveKey("X-Server-Name")) and
        ((v \ "route" \ "upstream") match { case u =>
          ((u \ "scheme").as[String] must_== "https") and
          ((u \ "host").as[String] must_== "localhost") and
          ((u \ "port").as[Int] must_== 8080)
        })

      })
    }

  }

  def when[A](subject: A)(cond: A => Boolean): Future[Unit] = Future {
    while (!cond(subject)) {
      Thread.sleep(10)
    }
  }


  def collectionAgent[A](e: Enumerator[A]): Agent[Seq[A]] = {
    val agent: Agent[Seq[A]] = Agent(Nil)
    e run Iteratee.foreach { v =>
      agent send { _ :+ v }
    }
    agent
  }


  def contentAsJsonEnumerator(
      fResult: Future[Result])(implicit mat: Materializer): Enumerator[JsValue] = {
    contentAsStringEnumerator(fResult) &> Enumeratee.mapConcat { text =>
      text.split("\n").flatMap {
        case s if s.startsWith("data: ") =>
          Some(Json.parse(s.replaceFirst("data: ","")))
        case _ =>
          None
      }
    }
  }

  def contentAsStringEnumerator(
      fResult: Future[Result])(implicit mat: Materializer): Enumerator[String] = {
    val encoding = charset(fResult).getOrElse("utf-8")
    Enumerator.flatten(fResult.map { r =>
      val stringConv = Enumeratee.map[ByteString] { bytes =>
        bytes.decodeString(encoding)
      }
      val publisher = r.body.dataStream.runWith(
          Sink.asPublisher[ByteString](false))
      Streams.publisherToEnumerator(publisher) &> stringConv
    })
  }
}
