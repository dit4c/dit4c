package dit4c.gatehouse.auth

import com.nimbusds.jwt.JWTParser.{parse => parseJwt}
import com.nimbusds.jwt.JWT
import scala.collection.JavaConversions._

class AuthorizationChecker {

  type AuthorizationFlag = Boolean

  private val CLAIM_NAME = "http://dit4c.github.io/authorized_containers"

  def apply(containerName:String)(serializedJwt: String): AuthorizationFlag = {
    val jwt: JWT = parseJwt(serializedJwt)
    jwt.getJWTClaimsSet.getCustomClaim(CLAIM_NAME) match {
      case list: java.util.List[String] =>
        list.toList.toSet[String].contains(containerName)
      case notAList: Object =>
        // Bad format
        println(notAList)
        false
    }
  }

}