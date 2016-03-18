package models

import scala.concurrent.Future
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.matcher.Matcher
import play.api.test._
import play.api.libs.json._
import models._
import utils.SpecUtils
import providers.RoutingMapEmitter
import play.api.libs.iteratee._
import akka.stream.scaladsl.Sink

@RunWith(classOf[JUnitRunner])
class ChangeFeedSpec extends PlaySpecification with SpecUtils {

  import scala.concurrent.ExecutionContext.Implicits.global
  import ChangeFeed._

  val testImage = "dit4c/dit4c-container-ipython"

  "ChangeFeed" should {

    "should emit new events as DB is updated" in new WithApplication(fakeApp) {
      val emitter = app.injector.instanceOf(classOf[ChangeFeed])
      val computeNodeDao = app.injector.instanceOf(classOf[ComputeNodeDAO])
      val containerDao = app.injector.instanceOf(classOf[ContainerDAO])

      val fEvents = emitter.changes(None)(containerDao.containerFormat)
        .take(6)
        .runWith(Sink.fold(
            IndexedSeq.empty[Change[_ <: Container]])((m,v) => m :+ v))

      val session = new UserSession(db(app))
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            RoutingMapEmitter.Backend("localhost", 8080, "https")))
      val containers = IndexedSeq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )

      await {
        containers.foldLeft(Future.successful(())) { (f, c) =>
          f.flatMap(_ => c.delete)
        }
      }

      val events = await(fEvents)
      events.must(haveSize(6)) and
      events(0).must_==(Update(containers(0).id, containers(0))) and
      events(1).must_==(Update(containers(1).id, containers(1))) and
      events(2).must_==(Update(containers(2).id, containers(2))) and
      events(3).must_==(Deletion(containers(0).id)) and
      events(4).must_==(Deletion(containers(1).id)) and
      events(5).must_==(Deletion(containers(2).id))
    }

  }

}
