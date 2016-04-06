package views.js.models.viewdocs

import org.specs2.runner.JUnitRunner
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification
import org.junit.runner.RunWith
import org.mozilla.javascript._
import scala.collection.JavaConversions._
import org.specs2.ScalaCheck

@RunWith(classOf[JUnitRunner])
class FixedPropertyMapSpec extends Specification with ScalaCheck {

  type Ks = Seq[String]
  type KVs = Seq[(String,String)]

  "FixedPropertyMap" should {

    "produce compilable JS" ! prop { (pairs: KVs, sortKeys: Ks) =>
      fixed_property_map(pairs, sortKeys).body must compileAsJs
    }

  }

  val compileAsJs: Matcher[String] = beLike {
    case js: String =>
      val ctx = Context.enter()
      try {
        ctx.compileString(js, "test.js", 0, null)
        ok(js)
      } catch {
        case e: Throwable =>
          ko(s"Compile failed: ${e.getMessage}\n$js")
      }
  }

}
