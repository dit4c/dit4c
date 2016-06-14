package dit4c.scheduler.domain

import java.time.Instant

trait BaseDomainEvent {
  def timestamp: Instant
}