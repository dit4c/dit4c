package dit4c.scheduler.utils

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import org.scalacheck.Gen
import org.specs2.scalacheck.Parameters
import org.scalacheck.Arbitrary
import java.io.OutputStream

class SchedulerConfigParserSpec extends Specification
    with ScalaCheck with MatcherMacros {
  // Necessary for MatcherMacros
  import scala.language.experimental.macros

  implicit val params = Parameters(minTestsOk = 1000, workers = 20)

  val parser = new SchedulerConfigParser(new AppMetadata {
    def name = "scheduler-test"
    def version = "x.x.x"
  })

  "SchedulerConfigParser" >> {

    skipped

    // TODO: Update to handle specifying keys
    /*
    "port" >> {

      "can be 0" >> {
        parser.parse(Seq("-p", "0")) must {
          beSome[SchedulerConfig] {
            matchA[SchedulerConfig]
              .port(be_==(0))
          }
        }
      }

      "can be any valid TCP port" >> prop({ port: Int =>
        parser.parse(Seq("-p", port.toString)) must {
          beSome[SchedulerConfig] {
            matchA[SchedulerConfig]
              .port(be_==(port))
          }
        }
      }).setGen(Gen.choose(1, 0xFFFF - 1))

      "cannot be an invalid TCP port" >> prop({ port: Int =>
        Console.withErr(nullOutputStream) {
          parser.parse(Seq("-p", port.toString)) must beNone
        }
      }).setGen(Gen.oneOf(
          Gen.choose(Integer.MIN_VALUE, -1),
          Gen.choose(0xFFFF, Integer.MAX_VALUE)))

    }
    */
  }

  private def nullOutputStream = new OutputStream {
    def write(b: Int) = ()
  }

}