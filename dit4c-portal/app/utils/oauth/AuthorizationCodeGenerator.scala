package utils.oauth

import play.api.libs.json._
import pdi.jwt._
import scala.concurrent.duration._
import scala.util.Random
import scalaoauth2.provider.AuthInfo
import java.time.Instant
import scala.util.Try
import java.util.Base64

class AuthorizationCodeGenerator(
    secret: String,
    issuer: String = "portal",
    defaultLifetime: FiniteDuration = 10.minutes) {

  def create[P](payload: P, maxLifetime: FiniteDuration = defaultLifetime)(implicit wp: Writes[P]): String = {
    val now = Instant.now
    val claim = JwtClaim(
        issuer = Some(issuer),
        subject = Some(serializePayload(payload)),
        audience = Some(issuer),
        issuedAt = Some(now.getEpochSecond),
        expiration = Some(now.getEpochSecond + maxLifetime.toSeconds),
        jwtId = Some(Random.alphanumeric.take(20).mkString))
    Jwt.encode(claim, secret, jwtAlgorithm)
  }

  def decode[P](code: String)(implicit rp: Reads[P]): Try[P] =
    JwtJson.decode(code, secret, Seq(jwtAlgorithm))
      .filter { claim =>
        claim.issuer == Some(issuer) &&
        claim.audience == Some(issuer)
      }
      .map(_.subject.get)
      .map(deserializePayload[P])

  private val jwtAlgorithm = JwtAlgorithm.HS512

  protected def deserializePayload[P](s: String)(implicit rp: Reads[P]): P =
    Json.parse(Base64.getDecoder.decode(s)).as[P]

  protected def serializePayload[P](p: P)(implicit wp: Writes[P]): String =
    Base64.getEncoder.encodeToString(Json.stringify(Json.toJson(p)).getBytes)

}