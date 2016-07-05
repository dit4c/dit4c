package domain

import java.time.Instant

trait BaseDomainEvent {
  def timestamp: Instant
}