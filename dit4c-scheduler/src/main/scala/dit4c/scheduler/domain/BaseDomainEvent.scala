package dit4c.scheduler.domain

import com.google.protobuf.timestamp.Timestamp
import java.time.Instant

object BaseDomainEvent {
  import scala.language.implicitConversions

  implicit def timestamp2instant(ts: Timestamp): Instant =
    Instant.ofEpochSecond(ts.seconds, ts.nanos)

  implicit def instant2timestamp(instant: Instant): Timestamp =
    Timestamp(instant.getEpochSecond, instant.getNano)

  implicit def optTimestamp2optInstant(oi: Option[Instant]): Option[Timestamp] =
    oi.map(instant2timestamp)

  implicit def optInstant2optTimestamp(ots: Option[Timestamp]): Option[Instant] =
    ots.map(timestamp2instant)

  def now: Some[Timestamp] = Some(instant2timestamp(Instant.now))

}

trait BaseDomainEvent extends dit4c.common.ProtobufSerializable {
  def timestamp: scala.Option[Timestamp]
}
