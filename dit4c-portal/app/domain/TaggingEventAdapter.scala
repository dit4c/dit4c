package domain

import akka.persistence.journal.WriteEventAdapter
import akka.persistence.journal.Tagged
import play.api.Logger

object TaggingEventAdapter {

  def tagForUserCreatedInstance(instanceId: String) =
    s"user-creates-instance-${instanceId}"


}

class TaggingEventAdapter extends WriteEventAdapter {
  import TaggingEventAdapter._

  val log = Logger(getClass)

  override def toJournal(event: Any): Any =
    event match {
      case event: domain.user.CreatedInstance =>
        val tagged = Tagged(event, Set(tagForUserCreatedInstance(event.instanceId)))
        log.debug("Tagged: ${tagged}")
        tagged
      case _ => event
    }

  override def manifest(event: Any): String = ""

}