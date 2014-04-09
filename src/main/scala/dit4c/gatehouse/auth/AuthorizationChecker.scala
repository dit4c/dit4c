package dit4c.gatehouse.auth

import com.nimbusds.jwt.JWTParser.{parse => parseJwt}
import com.nimbusds.jwt.JWT
import scala.collection.JavaConversions._

class AuthorizationChecker {

  private val CLAIM_NAME = "http://dit4c.github.io/authorized_containers"

  def apply(containerName:String, serializedJwt: String): Either[String, Unit] = {
    val jwt: JWT = parseJwt(serializedJwt)
    jwt.getJWTClaimsSet.getCustomClaim(CLAIM_NAME) match {
      case list: java.util.List[_] =>
        val authorizedContainers =
          list.asInstanceOf[java.util.List[String]].toList.toSet[String]
        if (authorizedContainers.contains(containerName))
          Right()
        else
          Left("Container not present in authorization list")
      case notAList: Object =>
        // Bad format
        Left(s"Malformed authorization list: $notAList")
    }
  }

}