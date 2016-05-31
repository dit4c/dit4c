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

  class RktRunner(
      val ce: CommandExecutor,
      val rktDir: Path,
      val systemdUnitPrefix: String = "dit4c-instance-")(
          implicit ec: ExecutionContext) {

    def start(instanceId: String, imageName: String) = ???

    protected[runner] def listSystemdUnits: Future[Set[SystemdUnit]] =
      systemctlCmd
        .flatMap { bin => ce(bin :+ "list-units" :+ "--no-legend" :+ s"$systemdUnitPrefix*") }
        .map(_.trim)
        .map { output =>
          output.lines.map { line =>
            var parts = line.split("""\s+""").toList
            val (name :: _) = parts
            SystemdUnit(name.stripSuffix(".service"))
          }.toSet
        }

    /**
     * List rkt pods. Runs as root.
     */
    protected[runner] def listRktPods: Future[Set[RktPod]] =
      privilegedRktCmd
        .flatMap { rktCmd => ce(rktCmd :+ "list" :+ "--full" :+ "--no-legend") }
        .map(_.trim)
        .map { output =>
          output.lines.toSeq.flatMap {
            case line if line.matches("""\s.*""") =>
              None
            case line =>
              var parts = line.split("""(\t|\s\s+)""").toList
              val (uuid :: _ :: _ :: _ :: state :: _) = parts
              Some(RktPod(uuid, RktPod.States.fromString(state).get))
          }.toSet
        }

    protected def rktCmd: Future[Seq[String]] = which("rkt").map(_ :+ s"--dir=$rktDir")

    protected def systemdRunCmd: Future[Seq[String]] = which("systemd-run")

    protected def systemctlCmd: Future[Seq[String]] = which("systemctl")

    protected def privilegedRktCmd: Future[Seq[String]] =
      rktCmd.map(Seq("sudo", "-n", "--") ++ _)

    private def which(cmd: String): Future[Seq[String]] =
      ce(Seq("which", cmd))
        .map(_.trim)
        .map {
          case s if s.isEmpty => throw new Exception(s"`which $cmd` was blank")
          case s => Seq(s)
        }

  }

  case class Instance(
      instanceId: String,
      rktPod: Option[RktPod],
      systemdUnit: Option[SystemdUnit])

  case class RktPod(uuid: String, state: RktPod.States.Value)

  case class SystemdUnit(name: String)

  object RktPod {
    object States extends Enumeration {
      val Prepared, Running, Exited = Value

      def fromString(v: String) =
        values.find(s => s.toString.toLowerCase.equals(v.toLowerCase))
    }
  }

}