package dit4c.scheduler.domain

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.execute.Result

class InstanceSpec(implicit ee: ExecutionEnv) extends Specification {

  "Instance" >> {

    "IDs should be 160-bit" >> {
      Result.foreach(Seq.fill(10000)(Instance.newId)) { id =>
        id must {
          haveLength[String](32) and
          beMatching("[0-9a-f]+")
        }
      }
    }

    "IDs should be generated in lexical order" >> {
      val ids = Seq.fill(1000)({ Thread.sleep(1); Instance.newId })
      ids.sorted must_== ids
    }

  }

}