package providers

import scala.concurrent.Future
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import play.api.test._
import play.api.libs.json._
import models._
import utils.SpecUtils
import providers.hipache.Hipache
import play.api.libs.iteratee._
import scala.concurrent.Promise

@RunWith(classOf[JUnitRunner])
class RoutingMapEmitterSpec extends PlaySpecification with SpecUtils {
  import RoutingMapEmitter._

  val testImage = "dit4c/dit4c-container-ipython"

  "RoutingMapEmitter" should {

    "should emit empty routing map if DB is empty" in new WithApplication(fakeApp) {
      val emitter = app.injector.instanceOf(classOf[RoutingMapEmitter])
      val fEvent = emitter.newFeed |>>> Iteratee.head

      val event: Option[Event] = await(fEvent)
      event must beSome(beAnInstanceOf[ReplaceAllRoutes])
      val ReplaceAllRoutes(routes) = event.get
      routes must beEmpty
    }

    "should emit current routing map if containers exist" in new WithApplication(fakeApp) {
      val emitter = app.injector.instanceOf(classOf[RoutingMapEmitter])

      val session = new UserSession(db(app))
      val computeNodeDao = app.injector.instanceOf(classOf[ComputeNodeDAO])
      val containerDao = app.injector.instanceOf(classOf[ContainerDAO])
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            Hipache.Backend("localhost", 8080, "https")))
      val containers = Seq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )

      val ReplaceAllRoutes(routes) = await {
        val p = Promise[Event]()
        emitter.newFeed |>>> Iteratee.foreach {
          p.success(_)
        }
        p.future
      }
      routes must haveSize(3)
      routes.map(_.backend) must contain(be_==(computeNode.backend))

      await(Future.sequence(containers.map(_.delete)));
      // Brief wait to ensure event feed has caught up
      Thread.sleep(100)

      val ReplaceAllRoutes(routesAfterDelete) = await {
        val p = Promise[Event]()
        emitter.newFeed |>>> Iteratee.foreach {
          p.success(_)
        }
        p.future
      }
      routesAfterDelete must beEmpty
    }

    "should emit new events as DB is updated" in new WithApplication(fakeApp) {
      val emitter = app.injector.instanceOf(classOf[RoutingMapEmitter])

      val session = new UserSession(db(app))

      val fEvents = emitter.newFeed |>>> Iteratee.takeUpTo(7)
      val computeNodeDao = app.injector.instanceOf(classOf[ComputeNodeDAO])
      val containerDao = app.injector.instanceOf(classOf[ContainerDAO])
      val computeNode =
        await(computeNodeDao.create(
            session.user, "Local", "fakeid", "http://localhost:5000/",
            Hipache.Backend("localhost", 8080, "https")))
      val containers = Seq(
        await(containerDao.create(session.user, "name1", testImage, computeNode)),
        await(containerDao.create(session.user, "name2", testImage, computeNode)),
        await(containerDao.create(session.user, "name3", testImage, computeNode))
      )
      await(Future.sequence(containers.map(_.delete)))

      val events: IndexedSeq[Event] = await(fEvents).toIndexedSeq
      events must haveSize(7)
      events(0) must_== ReplaceAllRoutes(Set())
      events(1) must beAnInstanceOf[SetRoute]
      events(2) must beAnInstanceOf[SetRoute]
      events(3) must beAnInstanceOf[SetRoute]
      events(4) must beAnInstanceOf[DeleteRoute]
      events(5) must beAnInstanceOf[DeleteRoute]
      events(6) must beAnInstanceOf[DeleteRoute]
    }

  }


  val tapEcho: Enumeratee[Event,Event] = Enumeratee.map {
    case e: Event => println(e); e
  }

}
