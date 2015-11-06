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

@RunWith(classOf[JUnitRunner])
class BufferingSpec extends Specification {

  import scala.concurrent.ExecutionContext.Implicits.global

  "Buffering.diskBuffer" should {

    "not change elements" >> {
      val seed = Random.nextLong
      def testStream: Stream[ByteString] = {
        val r = new Random(seed)
        Stream.continually(ByteString(r.nextString(10240))).take(10000)
      }
      def testEnumerator = Enumerator.enumerate(testStream)
      def testIteratee =
        Iteratee.fold[ByteString,MessageDigest](
            MessageDigest.getInstance("SHA-512")) { (md, v) =>
              md.update(v.toArray); md
            }
      val v1 = Await.result(
        (testEnumerator run testIteratee).map {
          _.digestToByteString
        }
      , 20.seconds)
      val v2 = Await.result(
        (Buffering.diskBuffer(testEnumerator) run testIteratee).map {
          _.digestToByteString
        }
      , 20.seconds)
      v2 must_== v1
    }
  }

  implicit class MessageDigestByteStringGenerator(md: MessageDigest) {
    def digestToByteString = ByteString(md.digest)
  }


}