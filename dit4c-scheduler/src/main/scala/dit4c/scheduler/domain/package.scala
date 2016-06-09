package dit4c.scheduler

package object domain {

  type ZoneType = ZoneTypes.Value
  object ZoneTypes extends Enumeration {
    val Rkt = Value("rkt")
  }


}