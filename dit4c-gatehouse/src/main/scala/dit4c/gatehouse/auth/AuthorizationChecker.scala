package dit4c.gatehouse.auth

import com.nimbusds.jwt.{JWT, JWTParser}
import scala.collection.JavaConversions._
import java.text.ParseException

class AuthorizationChecker {

  private val CLAIM_NAME = "http://dit4c.github.io/authorized_containers"

  def apply(serializedJwt: String, containerName:String): Either[String, Unit] = {
    try {
      val jwt: JWT = JWTParser.parse(serializedJwt)
      jwt.getJWTClaimsSet.getCustomClaim(CLAIM_NAME) match {
        case list: java.util.List[_] =>
          val authorizedContainers =
            list.asInstanceOf[java.util.List[String]].toList.toSet[String]
          if (authorizedContainers.contains(containerName))
            Right(())
          else
            Left("Container not present in authorization list")
        case notAList: Object =>
          // Bad format
          Left(s"Malformed authorization list: $notAList")
      }
    } catch {
      case _: ParseException =>
        Left(s"Invalid JSON Web Token: $serializedJwt")
    }
  }

}