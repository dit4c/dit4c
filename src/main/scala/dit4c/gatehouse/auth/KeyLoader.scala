package dit4c.gatehouse.auth

import java.io.InputStream
import com.nimbusds.jose.jwk.RSAKey

object KeyLoader {

  implicit def toPublic(keys: Seq[RSAKey]) = keys.map(_.toRSAPublicKey)

  def apply(input: InputStream): Seq[RSAKey] = {
    val content = scala.io.Source.fromInputStream(input).mkString
    import spray.json._
    import DefaultJsonProtocol._
    JsonParser(content).convertTo[Seq[JsObject]].map { obj: JsObject =>
      RSAKey.parse(obj.compactPrint)
    }
  }

}