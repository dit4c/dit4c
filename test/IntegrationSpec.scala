import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.test._
import play.api.test.Helpers._

/**
 * add your integration spec here.
 * An integration test will fire up a whole play application in a real (or headless) browser
 */
@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends Specification {

  import testing.TestUtils.fakeApp

  "Application" should {

    "work from within a browser" in new WithBrowser(app = fakeApp) {
      browser.goTo("http://localhost:" + port)
      skipped // TODO; Implement a proper test
      browser.pageSource must contain("Pick a container")
    }
  }
}
