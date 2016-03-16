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

@RunWith(classOf[JUnitRunner])
class ChangeFeedSpec extends PlaySpecification with SpecUtils {

  import scala.concurrent.ExecutionContext.Implicits.global
  import ChangeFeed._

  val testImage = "dit4c/dit4c-container-ipython"

  "ChangeFeed" should {

    "should emit new events as DB is updated" in new WithApplication(fakeApp) {
      /*
      val emitter = app.injector.instanceOf(classOf[ChangeFeed])

      val fEvents = emitter.changes |>>> Iteratee.takeUpTo(8)

      val session = new UserSession(db(app))
      val computeNodeDao = app.injector.instanceOf(classOf[ComputeNodeDAO])
      val containerDao = app.injector.instanceOf(classOf[ContainerDAO])
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

      val events: IndexedSeq[Change] = await(fEvents).toIndexedSeq
      events must haveSize(8)
      events(0) must haveDocType("User")
      events(1) must haveDocType("ComputeNode")
      events(2) must haveDocType("Container")
      events(3) must haveDocType("Container")
      events(4) must haveDocType("Container")
      events(5) must_== Deletion(containers(0).id)
      events(6) must_== Deletion(containers(1).id)
      events(7) must_== Deletion(containers(2).id)
      */
      pending
    }

  }

  /*
  def haveDocType(t: String): Matcher[ChangeFeed.Change] = { change: Change =>
    change match {
      case Update(_, doc) =>
        (doc \ "type").asOpt[String] match {
          case Some(`t`) => (true, "")
          case Some(s) => (false, s"Doc type does not match: $s != $t")
          case None => (false, "Doc type is missing from document")
        }
      case _: Change =>
        (false, "Change is not an update")
    }
  }
  */

}
