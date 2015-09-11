package dit4c.switchboard

import java.io.File
import org.bouncycastle.openssl.PEMParser
import java.io.FileReader
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import com.typesafe.scalalogging.LazyLogging
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ECParameters

case class TlsConfig(
  val certificateFile: File,
  val keyFile: File) extends LazyLogging {

  val certificate: X509CertificateHolder =
    parsePEMFile(certificateFile) match {
      case o: X509CertificateHolder => o
      case other =>
        throw new Exception(s"Unexpected content for certificate: $other")
    }

  (new FileReader(keyFile)) // Issue FileNotFound if the file doesn't exist

  private def parsePEMFile(f: File): AnyRef =
    (new PEMParser(new FileReader(f))).readObject

}