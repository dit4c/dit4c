package dit4c.machineshop.auth

import com.nimbusds.jose.jwk.JWKSet
import scala.collection.JavaConversions._

class SignatureVerifier(publicKeys: JWKSet) {

  publicKeys.getKeys.toSeq

}