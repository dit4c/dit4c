package dit4c.switchboard

import org.specs2.mutable.Specification
import scala.sys.process.Process
import java.nio.file.Files
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.execute.Result
import org.specs2.matcher.PathMatchers
import java.io.File
import org.bouncycastle.openssl.PEMParser
import java.io.FileReader
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMKeyPair
import java.io.FileNotFoundException

class TlsConfigSpec extends Specification with PathMatchers {

  import scala.concurrent.ExecutionContext.Implicits.global

  "TlsConfig should" >> {

    "validate its files" >> {
      // Test with non-existent files
      { TlsConfig(new File("doesnotexist"), fileInClasspath("test.key")) } must
        throwA[FileNotFoundException]

      { TlsConfig(fileInClasspath("test.crt"), new File("doesnotexist")) } must
        throwA[FileNotFoundException]

      // Test with files that isn't a certificate
      { TlsConfig(fileInClasspath("test.key"), fileInClasspath("test.key")) } must
        throwA[Exception]

      // Test the other way is intentionally omitted, as reading the ECDSA key
      // causes an exception.

      // Test good content yields success
      { TlsConfig(fileInClasspath("test.crt"), fileInClasspath("test.key")) } must
        not(throwA[Exception])

      { TlsConfig(fileInClasspath("test2.crt"), fileInClasspath("test2.key")) } must
        not(throwA[Exception])
    }
  }

  def fileInClasspath(f: String) = new File(this.getClass.getResource(f).toURI)

}