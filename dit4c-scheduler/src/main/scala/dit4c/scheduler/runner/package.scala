package dit4c.scheduler

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

package object runner {

  type CommandExecutor =
    (  Seq[String],    // Command to run
       InputStream,    // StdIn
       OutputStream,   // StdOut
       OutputStream    // StdErr
      ) => Future[Int] // Exit code

  implicit class CommandExecutorHelper(ce: CommandExecutor) {

    def apply(command: Seq[String], in: InputStream = emptyInputStream)(
        implicit ec: ExecutionContext): Future[String] = {
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      ce(command, in, out, err).flatMap {
        case 0 => Future.successful(out.getAsString)
        case _ => Future.failed(new Exception(err.getAsString+" from "+command))
      }
    }

    private def emptyInputStream = new InputStream() { override def read = -1 }

    implicit class BaosHelper(os: ByteArrayOutputStream) {
      def getAsString = new String(os.toByteArray, "utf8")
    }
  }

  case class Instance(
      instanceId: String,
      rktPod: Option[RktPod],
      systemdUnit: Option[SystemdUnit])

  case class RktPod(uuid: String, apps: Set[String], state: RktPod.States.Value)

  case class SystemdUnit(name: String)

  object RktPod {
    object States extends Enumeration {
      val Prepared, Running, Exited, Unknown = Value

      def fromString(v: String) =
        values
          .find(s => s.toString.toLowerCase.equals(v.toLowerCase))
          .getOrElse(Unknown)
    }
  }

}
