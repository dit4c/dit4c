package views.js.models.viewdocs

import org.specs2.runner.JUnitRunner
import org.specs2.matcher.Matcher
import org.specs2.mutable.Specification
import org.junit.runner.RunWith
import org.mozilla.javascript._
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class FixedPropertyMapSpec extends Specification {

  "FixedPropertyMap" should {

    "produce compilable JS" in {
      fixed_property_map(Seq.empty, Seq.empty).body.must(compileAsJs) and
      fixed_property_map(
          ("type", "Foo") :: Nil,
          Seq.empty).body.must(compileAsJs) and
      fixed_property_map(
          ("type", "Event") :: ("subtype", "Dummy") :: Nil,
          "id" :: "createdAt" :: Nil).body.must(compileAsJs)
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
