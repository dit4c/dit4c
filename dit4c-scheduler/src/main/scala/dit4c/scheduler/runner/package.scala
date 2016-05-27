package dit4c.scheduler

import java.io.InputStream
import java.io.OutputStream
import scala.concurrent.Future
import java.nio.file.Path
import scala.concurrent.ExecutionContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

package object runner {

  type CommandExecutor =
    (  String,         // Command to run
       InputStream,    // StdIn
       OutputStream,   // StdOut
       OutputStream    // StdErr
      ) => Future[Int] // Exit code

  class RktRunner(
      val commandExecutor: CommandExecutor,
      val dir: Path)(implicit ec: ExecutionContext) {

    def list: Future[Set[RktPod]] =
      privilegedRktCmd
        .flatMap { rktCmd => exec(s"$rktCmd list --full --no-legend") }
        .map(_.trim) // Get rid of trailing new line
        .map { output =>
          output.lines.map { line =>
            var parts = line.split("""(\t|\s\s+)""").toList
            val (uuid :: app :: imageName :: imageId :: state :: _) = parts
            RktPod(uuid, RktPod.States.fromString(state).get)
          }.toSet
        }

    protected def rktCmd: Future[String] =
      exec("which rkt")
        .map(_.trim)
        .map {
          case s if s.isEmpty => throw new Exception("`which rkt` was blank")
          case s => s
        }
        .map { (rktPath: String) =>
          Seq(rktPath.trim, s" --dir=${dir}").mkString(" ")
        }
    protected def privilegedRktCmd: Future[String] = rktCmd.map("sudo -n -- "+_)

    protected def exec(command: String): Future[String] = {
      val in = new ByteArrayInputStream(Array.empty[Byte])
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      commandExecutor(command, in, out, err).flatMap {
        case 0 => Future.successful(out.getAsString)
        case _ => Future.failed(new Exception(err.getAsString))
      }
    }

    implicit class BaosHelper(os: ByteArrayOutputStream) {
      def getAsString = new String(os.toByteArray, "utf8")
    }
  }

  case class RktPod(uuid: String, state: RktPod.States.Value)

  object RktPod {
    object States extends Enumeration {
      val Prepared, Running, Exited = Value

      def fromString(v: String) =
        values.find(s => s.toString.toLowerCase.equals(v.toLowerCase))
    }
  }

}