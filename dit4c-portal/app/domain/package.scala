import dit4c.common.KryoSerializable
import dit4c.common.ProtobufSerializable
import java.time.Instant
import com.google.protobuf.timestamp.Timestamp

package object domain {

  object BaseDomainEvent {
    import scala.language.implicitConversions

    implicit def instant2timestamp(instant: Instant): Timestamp =
      Timestamp(instant.getEpochSecond, instant.getNano)

    def now: Some[Timestamp] = Some(instant2timestamp(Instant.now))
  }

  trait BaseDomainEvent extends ProtobufSerializable
  trait BaseCommand extends KryoSerializable
  trait BaseResponse extends KryoSerializable

}