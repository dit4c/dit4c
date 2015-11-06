package utils

import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.libs.iteratee.Enumerator
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.iteratee.Iteratee
import akka.util.ByteString
import java.security.MessageDigest
import scala.util.Random
import play.api.libs.iteratee.Enumeratee

@RunWith(classOf[JUnitRunner])
class BufferingSpec extends Specification {

  import scala.concurrent.ExecutionContext.Implicits.global

  "Buffering.diskBuffer" should {

    "not change elements" >> {
      val seed = Random.nextLong
      val v1 = Await.result(
        (testEnumerator(seed) run testIteratee)
      , 20.seconds)
      val v2 = Await.result(
        (Buffering.diskBuffer(testEnumerator(seed)) run testIteratee)
      , 20.seconds)
      v2 must_== v1
    }

    "handle partly-consumed enumerators" >> {
      val seed = Random.nextLong
      val v = Await.result(
        (Buffering.diskBuffer(testEnumerator(seed)) &> Enumeratee.take(20)
            run testIteratee)
      , 20.seconds)
      done
    }
  }

  def testEnumerator(seed: Long): Enumerator[ByteString] = {
    val r = new Random(seed)
    Enumerator.enumerate(
      Stream.continually(ByteString(r.nextString(10240))).take(10000))
  }

  def testIteratee =
        (Iteratee.fold[ByteString,MessageDigest](
            MessageDigest.getInstance("SHA-512")) { (md, v) =>
              md.update(v.toArray); md
            }).map(md => ByteString(md.digest))

}