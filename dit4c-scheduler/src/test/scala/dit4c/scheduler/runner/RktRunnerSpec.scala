package dit4c.scheduler.runner

import org.specs2.mutable.Specification
import java.io.InputStream
import java.io.OutputStream
import scala.sys.process.Process
import org.apache.sshd.common.util.io.IoUtils
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import scala.concurrent.Future
import scala.sys.process.ProcessIO
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import org.specs2.concurrent.ExecutionEnv
import java.nio.file.Paths
import org.specs2.matcher.MatcherMacros
import scala.concurrent.duration._
import scala.concurrent.Promise
import java.io.PipedOutputStream
import java.io.PipedInputStream
import java.io.ByteArrayOutputStream
import scala.util.Random
import scala.concurrent.Await
import scala.sys.process.ProcessLogger
import org.specs2.specification.BeforeEach

class RktRunnerSpec(implicit ee: ExecutionEnv)
    extends Specification with BeforeEach with MatcherMacros {

  import dit4c.scheduler.runner.CommandExecutorHelper

  val testImageUrl: URL = new URL(
    "https://quay.io/c1/aci/quay.io/prometheus/busybox/latest/aci/linux/amd64/")
  val testImageFile: Path = {
    val imagePath = Paths.get("rkt-test-image.aci")
    if (Files.exists(imagePath)) {
      imagePath
    } else {
      import sys.process._
      val tempFilePath = Files.createTempFile("rkt-", "-test-image.aci")
      testImageUrl.#>(tempFilePath.toFile).!!
      Files.move(tempFilePath, imagePath)
      imagePath
    }
  }

  val testImage = testImageFile.toAbsolutePath.toString
  val commandExecutor = (LocalCommandExecutor.apply _)
  implicit val executionContext = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool())

  def before = {
    // Check we have sudo to root
    val sb = new StringBuffer
    Process("sudo -nv").!<(ProcessLogger(s => sb.append(s))) match {
      case 0 => ok // We have root
      case _ => skipped(sb.toString)
    }
  }

  sequential

  "RktRunner" >> {

    "listSystemdUnits" >> {

      "initially return empty" >> {
        val runner = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        runner.listSystemdUnits must beEmpty[Set[SystemdUnit]].awaitFor(10.seconds)
      }

      "should show running units with prefix" >> {
        import scala.language.experimental.macros
        val prefix = "listSystemdUnits-test-"
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir,
            prefix)
        val systemdRunCmd = Seq("sudo", "-n", "--", "systemd-run")
        val instanceId = Random.alphanumeric.take(40).mkString
        // Prepared a unit
        Await.ready(
          commandExecutor(systemdRunCmd ++ Seq(s"--unit=${prefix}-${instanceId}.service", "/bin/sh", "-c", "while true; do sleep 1; done")),
          1.minute)
        // Check listing
        try {
          runner.listSystemdUnits must {
            haveSize[Set[SystemdUnit]](1) and contain(
              matchA[SystemdUnit]
                .name(s"${prefix}-${instanceId}")
            )
          }.awaitFor(1.minute)
        } finally {
          Await.ready(
            commandExecutor(Seq("sudo", "-n", "systemctl", "stop", s"${prefix}-${instanceId}.service")),
            1.minute)
        }
      }

    }

    "listRktPods" >> {

      "initially return empty" >> {
        val runner = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        runner.listRktPods must beEmpty[Set[RktPod]].awaitFor(10.seconds)
      }

      "should show prepared pods" >> {
        import scala.language.experimental.macros
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir)
        val rktCmd = Seq("sudo", "-n", "--",
          rktBinaryPath,
          "--dir=" + runner.rktDir)
        // Prepared a pod
        Await.ready(
          commandExecutor(rktCmd ++ Seq("prepare", "--insecure-options=image", testImage, "--exec", "/bin/true")),
          1.minute)
        // Check listing
        runner.listRktPods must {
          haveSize[Set[RktPod]](1) and contain(
            matchA[RktPod]
              .uuid(not(beEmpty[String]))
              .state(be(RktPod.States.Prepared))
          )
        }.awaitFor(1.minute)
      }

      "should show running pods" >> {
        import scala.language.experimental.macros
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir)
        val rktCmd = Seq("sudo", "-n", "--",
          rktBinaryPath,
          "--dir=" + runner.rktDir)
        // Run a pod
        val runOutput = new ByteArrayOutputStream()
        val p = Promise[Int]()
        val toProc = new InputStream() {
          override def read = Await.result(p.future, 2.minutes)
        }
        val readyToken = "ready-"+Random.alphanumeric.take(40).mkString
        val imageId = Await.result(
            commandExecutor(
              rktCmd ++ Seq("fetch", "--insecure-options=image", testImage)),
            1.minute).trim
        val manifestFile = {
          val manifest = s"""|{
                             |    "acVersion": "0.8.4",
                             |    "acKind": "PodManifest",
                             |    "apps": [
                             |        {
                             |            "name": "running-test",
                             |            "image": {
                             |                "id": "$imageId"
                             |            },
                             |            "app": {
                             |                "exec": [
                             |                    "/bin/sh",
                             |                    "-c",
                             |                    "echo $readyToken && cat -"
                             |                ],
                             |                "group": "99",
                             |                "user": "99"
                             |            },
                             |            "readOnlyRootFS": true
                             |        }
                             |    ]
                             |}""".stripMargin
          val path = Files.createTempFile("manifest-", ".json")
          Files.write(path, manifest.getBytes)
          path.toFile.deleteOnExit
          path
        }
        commandExecutor(
          rktCmd ++ Seq("run", "--interactive", s"--pod-manifest=$manifestFile"),
          toProc,
          runOutput,
          nullOutputStream)
        // Wait for the pod to start
        while (!runOutput.toByteArray.containsSlice(readyToken.getBytes)) {
          Thread.sleep(100)
        }
        // Check listing
        try {
          runner.listRktPods must {
            haveSize[Set[RktPod]](1) and contain(
              matchA[RktPod]
                .uuid(not(beEmpty[String]))
                .state(be(RktPod.States.Running))
            )
          }.awaitFor(1.minute)
        } finally {
          p.success(-1)
        }
      }

      "should show exited pods" >> {
        import scala.language.experimental.macros
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir)
        val rktCmd = Seq("sudo", "-n", "--",
          rktBinaryPath,
          "--dir=" + runner.rktDir)
        // Run a pod
        Await.ready(
          commandExecutor(
              rktCmd ++ Seq("run", "--insecure-options=image", "--net=none", testImage, "--exec", "/bin/true")),
          1.minute)
        // Check listing
        runner.listRktPods must {
          haveSize[Set[RktPod]](1) and contain(
            matchA[RktPod]
              .uuid(not(beEmpty[String]))
              .state(be(RktPod.States.Exited))
          )
        }.awaitFor(1.minute)
      }

      "should list multiple pods" >> {
        import scala.language.experimental.macros
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir)
        val rktCmd = Seq("sudo", "-n", "--",
          rktBinaryPath,
          "--dir=" + runner.rktDir)
        // Prepared a bunch of pods
        val numOfPods = 10
        1.to(numOfPods).foreach { _ =>
          Await.ready(
            commandExecutor(
                rktCmd ++ Seq("prepare", "--no-overlay", "--insecure-options=image", testImage, "--exec", "/bin/true")),
            1.minute)
        }
        // Check listing
        runner.listRktPods must {
          haveSize[Set[RktPod]](numOfPods) and contain(
            matchA[RktPod]
              .uuid(not(beEmpty[String]))
              .state(be(RktPod.States.Prepared))
          )
        }.awaitFor(1.minutes)
      }

    }

  }

  lazy val rktBinaryPath =
    Await.result(commandExecutor(Seq("which", "rkt")), 10.seconds).trim

  private def createTemporaryRktDir = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val dir = Files.createTempDirectory("rkt-tmp-")
    dir.toFile.deleteOnExit
    commandExecutor(Seq(
        rktBinaryPath,
        s"--dir=$dir",
        "list"))
    dir
  }

  private val nullLogger = ProcessLogger(_ => (), _ => ())
  private val nullOutputStream = new OutputStream() {
    override def write(b: Int) {}
  }

  /**
   * Runs all commands locally
   */
  object LocalCommandExecutor {

    implicit val executionContext = ExecutionContext.fromExecutorService(
        Executors.newCachedThreadPool())

    def apply(
        cmd: Seq[String],
        in: InputStream,
        out: OutputStream,
        err: OutputStream): Future[Int] = {
      val (pIO, done) = processIO(in, out, err)
      Future(Process(cmd).run(pIO).exitValue)
        .flatMap(exitValue => done.map(_ => exitValue))
    }

    protected def processIO(
        in: InputStream,
        out: OutputStream,
        err: OutputStream): (ProcessIO, Future[Unit]) =  {
      def pComplete[A](f: A => Future[Unit], p: Promise[Unit]): A => Unit =
        f.andThen(f => f.foreach(_ => p.trySuccess(())))
      // Three streams to complete => three promises to fulfill
      val (p1, p2, p3) = (Promise[Unit](), Promise[Unit](), Promise[Unit]())
      val cIn = pComplete(copyInput(in), p1)
      val cOut = pComplete(copyOutput(out), p2)
      val cErr = pComplete(copyOutput(err), p3)
      // Future completes after all streams have closed
      (new ProcessIO(cIn, cOut, cErr),
          Future.sequence(Seq(p1.future, p2.future, p3.future)).map(_ => ()))
    }

    def copyInput(from: InputStream) = (to: OutputStream) => copy(from, to)
    def copyOutput(to: OutputStream) = (from: InputStream) => copy(from, to)

    def copy(from: InputStream, to: OutputStream): Future[Unit] =
      Future(IoUtils.copy(from, to))

  }


}
