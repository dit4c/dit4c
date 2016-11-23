import dit4c.common.KryoSerializable
import dit4c.common.ProtobufSerializable
import java.time.Instant
import com.google.protobuf.timestamp.Timestamp

package object domain {

  object BaseDomainEvent {
    import scala.language.implicitConversions

    implicit def instant2timestamp(instant: Instant): Timestamp =
      Timestamp(instant.getEpochSecond, instant.getNano)

    implicit def timestamp2instant(ts: Timestamp): Instant =
      Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong)

    implicit def optTimestamp2optInstant(oi: Option[Instant]): Option[Timestamp] =
      oi.map(instant2timestamp)

    implicit def optInstant2optTimestamp(ots: Option[Timestamp]): Option[Instant] =
      ots.map(timestamp2instant)

    def now: Some[Timestamp] = Some(instant2timestamp(Instant.now))
  }

  trait BaseDomainEvent extends ProtobufSerializable
  trait BaseCommand extends KryoSerializable
  trait BaseResponse extends KryoSerializable

}