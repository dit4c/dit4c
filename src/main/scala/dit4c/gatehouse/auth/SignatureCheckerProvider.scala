package dit4c.gatehouse.auth

import java.io.File
import java.io.FileInputStream
import java.text.ParseException
import scala.concurrent._

object SignatureCheckerProvider {
  val log = java.util.logging.Logger.getLogger(this.getClass.getName)

  def fromFile(publicKeyFile: File)(implicit ec: ExecutionContext) = future {
    import KeyLoader._
    log.info(s"Retrieving keys from ${publicKeyFile.getAbsolutePath}")
    try {
      new SignatureChecker(KeyLoader(new FileInputStream(publicKeyFile)))
    } catch {
      case e: ParseException =>
        log.warning(
            s"No keys loaded. Unable to read public keys: ${e.getMessage}")
        new SignatureChecker(Nil)
    }
  }

}