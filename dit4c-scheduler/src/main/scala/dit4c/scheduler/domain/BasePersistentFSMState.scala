package dit4c.scheduler.domain

import akka.persistence.fsm.PersistentFSM

trait BasePersistentFSMState extends PersistentFSM.FSMState {
  override lazy val identifier: String =
    this.getClass.getSimpleName
      .stripSuffix("$")
      .flatMap(c => if (c.isUpper) s" $c" else c.toString)
      .trim
}