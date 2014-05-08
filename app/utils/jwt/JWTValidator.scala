package utils.jwt

import com.nimbusds.jwt.JWT

trait JWTValidator {

  // Process JWT and return payload if validation succeeds.
  def apply(jwt: JWT): Option[String]
}