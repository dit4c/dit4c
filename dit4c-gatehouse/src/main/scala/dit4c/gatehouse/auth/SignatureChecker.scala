package dit4c.gatehouse.auth

import java.security.interfaces.RSAPublicKey
import com.nimbusds.jose.crypto.RSASSAVerifier
import java.text.ParseException
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.JWTParser

class SignatureChecker(val publicKeys: Iterable[RSAPublicKey]) {

  val logger = java.util.logging.Logger.getAnonymousLogger

  val verifiers: Iterable[SignedJWT => Boolean] = publicKeys.map {k =>
    val impl = new RSASSAVerifier(k)

    (jws: SignedJWT) => {
      impl.verify(jws.getHeader, jws.getSigningInput, jws.getSignature)
    }
  }

  def apply(serializedJwt: String): Either[String, Unit] =
    try {
      JWTParser.parse(serializedJwt) match {
        case jws: SignedJWT => apply(jws)
        case _ =>
          Left(s"Not a JSON Web Signature: $serializedJwt")
      }
    } catch {
      case _: ParseException =>
        Left(s"Invalid JSON Web Token: $serializedJwt")
    }

  // If any verifier succeeds, then they all have
  def apply(jws: SignedJWT): Either[String, Unit] =
    if (verifiers.exists(f => f(jws)))
      Right(())
    else
      Left(s"Signature failed verification for all public keys: ${jws.serialize}")
}