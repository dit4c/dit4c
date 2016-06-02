package dit4c.scheduler.runner

import org.specs2.mutable.Specification
import java.io.InputStream
import java.io.OutputStream
import scala.sys.process.Process
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
import org.bouncycastle.util.io.TeeInputStream
import scala.sys.process.BasicIO
import java.io.ByteArrayInputStream
import scala.util._

class RktRunnerSpec(implicit ee: ExecutionEnv)
    extends Specification with BeforeEach with MatcherMacros {

  implicit val executionContext = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool())

  import dit4c.scheduler.runner.CommandExecutorHelper
  val commandExecutor = (LocalCommandExecutor.apply _)

  val testImageUrl: URL = new URL(
    "https://quay.io/c1/aci/quay.io/prometheus/busybox/latest/aci/linux/amd64/")
  val testImage: String = {
    val imagePath = "/tmp/rkt-test-image.aci"
    Await.result(
      commandExecutor(Seq("stat", imagePath))
        .map(_ => imagePath)
        .recover {
          case _ =>
            import sys.process._
            val os = new PipedOutputStream()
            val is = new PipedInputStream(os)
            // Create stream to write input to a file
            commandExecutor(Seq("sh", "-c", s"cat - > $imagePath"), is)
            // Stream from the provided URL to the piped output
            testImageUrl.#>(os).!!
            imagePath
        },
      1.minute)
  }

  def before = {
    Await.result(
      commandExecutor(Seq("sudo", "-nv"))
        .map { _ => ok }
        .recover { case e => skipped(e.getMessage) },
      1.minute)
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
            createTemporaryRktDir)
        runner.listRktPods must beEmpty[Set[RktPod]].awaitFor(10.seconds)
      }

      "should show prepared pods" >> {
        import scala.language.experimental.macros
        val runner = new RktRunner(
            commandExecutor,
            createTemporaryRktDir)
        // Prepared a pod
        Await.ready(
          commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("prepare", "--insecure-options=image", testImage, "--exec", "/bin/true")),
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
        // Run a pod
        val imageId = Await.result(
            commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("fetch", "--insecure-options=image", testImage)),
            1.minute).trim
        val runOutput = new ByteArrayOutputStream()
        val p = Promise[Int]()
        val toProc = new InputStream() {
          override def read = Await.result(p.future, 2.minutes)
        }
        val readyToken = "ready-"+Random.alphanumeric.take(40).mkString
        val manifest =
          s"""|{
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
              |        },
              |        {
              |            "name": "red-herring",
              |            "image": {
              |                "id": "$imageId"
              |            },
              |            "app": {
              |                "exec": [
              |                    "/bin/true"
              |                ],
              |                "group": "99",
              |                "user": "99"
              |            },
              |            "readOnlyRootFS": true
              |        }
              |    ]
              |}""".stripMargin
        commandExecutor(
            Seq("sh", "-c", "TMPFILE=$(mktemp /tmp/manifest-json-XXXXXXXX) && cat > $TMPFILE && echo $TMPFILE"),
            new ByteArrayInputStream((manifest+"\n").getBytes))
          .map(_.trim)
          .flatMap { manifestFile =>
            commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("run", "--interactive", s"--pod-manifest=$manifestFile"),
              toProc,
              runOutput,
              nullOutputStream)
          }
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
                .apps(be_==(Set("running-test", "red-herring")))
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
        // Run a pod
        Await.ready(
          commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("run", "--insecure-options=image", "--net=none", testImage, "--exec", "/bin/true")),
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
        // Prepared a bunch of pods
        val numOfPods = 10
        1.to(numOfPods).foreach { _ =>
          Await.ready(
            commandExecutor(
                rktCmd(runner.rktDir) ++
                Seq("prepare", "--no-overlay", "--insecure-options=image", testImage, "--exec", "/bin/true")),
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

    "fetch" >> {

      "should work with local files" >> {
        val runner = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        runner.fetch(testImage) must {
          beMatching("sha512-[0-9a-f]{64}".r)
        }.awaitFor(1.minutes)
      }

      "should not work with image IDs" >> {
        val runner = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        val imageId = Await.result(runner.fetch(testImage), 1.minute)
        runner.fetch(imageId) must {
          throwA[Exception]
        }.awaitFor(1.minutes)
      }

    }

  }

  lazy val rktBinaryPath =
    Await.result(commandExecutor(Seq("which", "rkt")), 10.seconds).trim

  private def rktCmd(rktDir: Path) = sudoCmd(rktBinaryPath, "--dir=" + rktDir)

  private def sudoCmd(xs: String*): Seq[String] = Seq("sudo", "-n", "--") ++ xs

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

    def apply(
        cmd: Seq[String],
        in: InputStream,
        out: OutputStream,
        err: OutputStream): Future[Int] = {
      // Use Docker container when testing on Travis CI
      val prependCmd =
        if (sys.env.contains("TRAVIS")) {
          Seq("docker", "exec", "-i", "rkt-systemd")
        } else Seq()
      val actualCmd = prependCmd ++ cmd
      // Travis CI will stall unless input is provided via <#(in) instead of
      // ProcessIO. It probably has something to do with subtle differences
      // in the way input is read from the stream.
      val process = Process(actualCmd).#<(in).run(processIO(out, err))
      // We could use a future, but this is a simpler way to wait for exitValue
      // on its own thread.
      val pExitValue = Promise[Int]()
      spawn(pExitValue.complete(Try(process.exitValue)))
      pExitValue.future
    }

    private def spawn(block: => Unit): Unit =
      (new Thread(new Runnable() {
        def run = block
      })).start

    private def processIO(out: OutputStream, err: OutputStream): ProcessIO =
      new ProcessIO(_ => (), copyOutput(out), copyOutput(err), true)

    private def copyOutput(to: OutputStream) =
      (from: InputStream) => BasicIO.transferFully(from, to)

  }


}
