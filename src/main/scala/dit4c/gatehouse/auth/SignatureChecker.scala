package dit4c.gatehouse.auth

import java.security.interfaces.RSAPublicKey
import com.nimbusds.jose.crypto.RSASSAVerifier
import java.text.ParseException
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.JWTParser

class SignatureChecker(val publicKeys: Set[RSAPublicKey]) {

  val logger = java.util.logging.Logger.getAnonymousLogger

  val verifiers: Set[SignedJWT => Boolean] = publicKeys.map {k =>
    val impl = new RSASSAVerifier(k)

    (jws: SignedJWT) => {
      impl.verify(jws.getHeader, jws.getSigningInput, jws.getSignature)
    }
  }

  def apply(serializedJwt: String): Boolean =
    try {
      JWTParser.parse(serializedJwt) match {
        case jws: SignedJWT => apply(jws)
        case _ =>
          logger.info(s"Not a JSON Web Signature: $serializedJwt")
          false
      }
    } catch {
      case _: ParseException =>
        logger.info(s"Invalid JSON Web Token: $serializedJwt")
        false
    }

  // If any verifier succeeds, then they all have
  def apply(jws: SignedJWT): Boolean = verifiers.exists(f => f(jws))
}