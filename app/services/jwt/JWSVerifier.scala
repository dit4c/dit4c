package services.jwt

import com.nimbusds.jwt._
import com.nimbusds.jose._

class JWSVerifier(val key: String) extends JWTValidator {

  def apply(jwt: JWT): Option[String] = jwt match {
    case jws: SignedJWT => apply(jws)
    case _ => None
  }

  def apply(jws: SignedJWT): Option[String] =
    if (JWSWrapper(jws).isValid)
      Some(jws.getPayload.toString)
    else
      None


  case class JWSWrapper(jws: SignedJWT) {
    import com.nimbusds.jose.crypto._
    import JWSAlgorithm._

    def isValid: Boolean = verifier match {
      case Some(v) => jws.verify(v)
      case None => false
    }

    def verifier = jws.getHeader.getAlgorithm match {
      // HMAC-SHA1 was used to sign
      case HS256 | HS384 | HS512 => Some(new MACVerifier(key))
      case _ => None
    }

  }

}