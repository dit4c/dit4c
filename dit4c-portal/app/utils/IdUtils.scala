package utils

import java.time.Instant
import scala.util.Random

object IdUtils {

  lazy val randomId: (Int) => String = {
    val base32 = (Range.inclusive('a','z').map(_.toChar) ++ Range.inclusive(2,7).map(_.toString.charAt(0)))
    (length: Int) =>
      Stream.continually(Random.nextInt(32)).map(base32).take(length).mkString
  }

  /**
   * Prefix based on time for easy sorting
   */
  def timePrefix = {
    val now = Instant.now
    f"${now.getEpochSecond}%016x".takeRight(10) + // 40-bit epoch seconds
    f"${now.getNano / 100}%06x"// 24-bit 100 nanosecond slices
  }

}