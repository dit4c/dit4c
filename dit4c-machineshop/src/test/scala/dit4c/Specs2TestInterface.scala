package dit4c

import akka.http.scaladsl.testkit.TestFrameworkInterface
import org.specs2.matcher.ThrownExpectations
import org.specs2.execute.FailureException

trait Specs2TestInterface extends TestFrameworkInterface with ThrownExpectations {

  override def cleanUp() {}

  override def failTest(msg: String) = throw new FailureException(failure(msg))

}