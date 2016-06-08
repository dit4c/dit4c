package dit4c.scheduler

import akka.http.scaladsl.testkit.TestFrameworkInterface
import org.specs2.execute.FailureException
import org.specs2.execute.Failure

trait Specs2TestFrameworkInterface extends TestFrameworkInterface {
  def failTest(msg: String): Nothing = {
    throw new FailureException(Failure(msg, stackTrace =
      (new Exception()).getStackTrace.toList))
  }

  override def cleanUp() = {}

}