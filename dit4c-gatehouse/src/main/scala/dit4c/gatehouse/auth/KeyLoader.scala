package dit4c.gatehouse.auth

import java.io.InputStream
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import scala.collection.JavaConversions._

object KeyLoader {

  implicit def toPublic(keys: Seq[RSAKey]) = keys.map(_.toRSAPublicKey)

  def apply(content: String): Seq[RSAKey] = {
    val keySet = JWKSet.parse(content)
    keySet.getKeys.map( _.asInstanceOf[RSAKey] )
  }

  def apply(input: InputStream): Seq[RSAKey] = {
    apply(scala.io.Source.fromInputStream(input).mkString)
  }

}