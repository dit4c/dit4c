package dit4c.scheduler

package object domain {

  type ClusterType = ClusterTypes.Value
  object ClusterTypes extends Enumeration {
    val Rkt = Value("rkt")
  }


}