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
import org.specs2.specification.ForEach
import org.specs2.execute.AsResult
import org.specs2.execute.Result
import org.specs2.ScalaCheck
import java.net.InetAddress
import java.net.NetworkInterface
import akka.http.scaladsl.Http
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.http.scaladsl.model._
import java.net.Inet4Address
import akka.http.scaladsl.model.headers.Authorization
import pdi.jwt.Jwt
import org.specs2.matcher.JsonMatchers
import akka.util.ByteString

class RktRunnerSpec(implicit ee: ExecutionEnv) extends Specification
    with BeforeEach with ScalaCheck with ForEach[RktRunnerImpl]
    with JsonMatchers with MatcherMacros {

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

  override def foreach[R: AsResult](f: RktRunnerImpl => R) = withRktDir { rktDir =>
    val runner = new RktRunnerImpl(commandExecutor, rktDir,
        "dit4c-test-"+Random.alphanumeric.take(10).mkString.toLowerCase)
    AsResult(f(runner))
  }


  sequential

  "RktRunner" >> {

    "which" >> {

      "fails if no command exists" >> { runner: RktRunnerImpl =>
        runner.which("doesnotexist") must {
          throwAn[Exception]
        }.awaitFor(1.minute)
      }

    }

    "listSystemdUnits" >> {

      "initially return empty" >> { runner: RktRunnerImpl =>
        runner.listSystemdUnits must beEmpty[Set[SystemdUnit]].awaitFor(1.minute)
      }

      "should show running units with prefix" >> { runner: RktRunnerImpl =>
        import scala.language.experimental.macros
        val prefix = runner.instanceNamePrefix
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

      "initially return empty" >> { runner: RktRunnerImpl =>
        runner.listRktPods must beEmpty[Set[RktPod]].awaitFor(10.seconds)
      }

      "should show prepared pods" >> { runner: RktRunnerImpl =>
        import scala.language.experimental.macros
        // Prepared a pod
        Await.ready(
          commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("prepare", "--insecure-options=image", "--no-overlay", testImage, "--exec", "/bin/true")),
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

      "should show running pods" >> { runner: RktRunnerImpl =>
        import scala.language.experimental.macros
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
            Seq("sh", "-c", Seq(
                "TMPFILE=$(mktemp --tmpdir manifest-json-XXXXXXXX)",
                "cat > $TMPFILE",
                "echo $TMPFILE").mkString(" && ")),
            new ByteArrayInputStream((manifest+"\n").getBytes))
          .map(_.trim)
          .flatMap { manifestFile =>
            commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("run", "--interactive", "--no-overlay", "--net=none", s"--pod-manifest=$manifestFile"),
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

      "should show exited pods" >> { runner: RktRunnerImpl =>
        import scala.language.experimental.macros
        // Run a pod
        Await.ready(
          commandExecutor(
              rktCmd(runner.rktDir) ++
              Seq("run", "--insecure-options=image", "--no-overlay", "--net=none", testImage, "--exec", "/bin/true")),
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

      "should list multiple pods" >> { runner: RktRunnerImpl =>
        import scala.language.experimental.macros
        // Prepared a bunch of pods
        val numOfPods = 10
        1.to(numOfPods).foreach { _ =>
          Await.ready(
            commandExecutor(
                rktCmd(runner.rktDir) ++
                Seq("prepare", "--insecure-options=image", "--no-overlay", testImage, "--exec", "/bin/true")),
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

      "should work with local files" >> { runner: RktRunnerImpl =>
        runner.fetch(testImage) must {
          beMatching("sha512-[0-9a-f]{64}".r)
        }.awaitFor(1.minutes)
      }

      "should not work with image IDs" >> { runner: RktRunnerImpl =>
        val imageId = Await.result(runner.fetch(testImage), 1.minute)
        runner.fetch(imageId) must {
          throwA[Exception]
        }.awaitFor(1.minutes)
      }

    }


    "guessServicePort" >> {
      "should work for" >> {
        def testImage(imageName: String, expectedPort: Int) =
          { runner: RktRunnerImpl =>
            // Use a long-running image for this test
            val imageId = Await.result(runner.fetch(imageName), 1.minute)
            runner.guessServicePort(imageId) must {
              be_==(expectedPort)
            }.awaitFor(1.minutes)
          }

        "nginx" >> testImage("docker://nginx:alpine", 80)
        "dit4c/gotty" >> testImage("docker://dit4c/gotty", 8080)
      }
    }

    "start/stop" >> {

      "only accepts lowercase alphanumeric prefixes" >> { runner: RktRunnerImpl =>
        runner.start(
            "NotGood",
            "sha512-"+Stream.fill(64)("0").mkString,
            new java.net.URL("http://example.test/doesnotexist")) must
          throwAn[IllegalArgumentException]
      }

      "should work with image IDs" >> { runner: RktRunnerImpl =>
        var podCallback: Option[HttpRequest] = None
        val serverBinding = {
          implicit val system = ActorSystem()
          implicit val materializer = ActorMaterializer()
          val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
            Http().bind(interface = hostIp.getHostAddress, port = 0)
          Await.result(
            serverSource.to(Sink.foreach { connection =>
              connection handleWithSyncHandler {
                case req: HttpRequest =>
                  podCallback = Some(req)
                  HttpResponse(200, entity = "")
              }
            }).run,
            1.minute)
        }
        val callbackUrl = new java.net.URL(Uri(
            "http",
            Uri.Authority(
                Uri.Host(serverBinding.localAddress.getAddress),
                serverBinding.localAddress.getPort),
            Uri.Path("/")).toString)
        import scala.language.experimental.macros
        // Use a long-running image for this test
        val imageId = Await.result(
            runner.fetch("docker://dit4c/gotty"), 1.minute)
        val instanceId = Random.alphanumeric.take(40).mkString.toLowerCase
        // Start image
        try {
          val instancePublicKey = Await.result(
              runner.start(instanceId, imageId, callbackUrl),
              5.minutes);
          {
            runner.listSystemdUnits must {
              haveSize[Set[SystemdUnit]](1) and contain(
                matchA[SystemdUnit]
                  .name(s"${runner.instanceNamePrefix}-${instanceId}")
              )
            }.awaitFor(1.minute)
          } and {
            Future(Thread.sleep(5000)).flatMap(_ => runner.listRktPods) must {
              haveSize[Set[RktPod]](1) and contain(
                matchA[RktPod]
                  .uuid(not(beEmpty[String]))
                  .apps(contain(s"${runner.instanceNamePrefix}-${instanceId}"))
                  .state(be(RktPod.States.Running))
              )
            }.awaitFor(1.minutes)
          } and {
            import org.specs2.matcher.JsonType._
            podCallback must beSome {
              matchA[HttpRequest]
                .method(be_==(HttpMethods.PUT))
                .headers(contain {
                  beAnInstanceOf[Authorization] and
                  (startWith("Bearer ") ^^ { h: HttpHeader => h.value }) and
                  (successfulTry ^^ { h: HttpHeader =>
                    // Token must decode with public key
                    Jwt.decodeAll(h.value.split(" ").last, instancePublicKey)
                  })
                })
                .entity(beLike[ResponseEntity] {
                  case e: HttpEntity.Strict => e must
                    matchA[HttpEntity.Strict]
                      .contentType(be_==(ContentTypes.`application/json`))
                      .data {
                    { /("ip").andHave(anyMatch) and
                      /("port").andHave(anyMatch) } ^^ {
                        bs: ByteString => bs.decodeString("utf8") }
                  }
                })
            }
          } and {
            runner.stop(instanceId) must {
              not(throwA[Exception])
            }.awaitFor(1.minutes)
          } and {
            runner.listRktPods must {
              haveSize[Set[RktPod]](1) and contain(
                matchA[RktPod]
                  .uuid(not(beEmpty[String]))
                  .apps(contain(s"${runner.instanceNamePrefix}-${instanceId}"))
                  .state(be(RktPod.States.Exited))
              )
            }.awaitFor(1.minutes)
          }
        } finally {
          serverBinding.unbind
        }
      }

    }

  }

  lazy val hostIp: InetAddress = {
    import scala.collection.JavaConversions._
    NetworkInterface.getNetworkInterfaces.toSeq
      .find(i => Set("eth0", "em1").contains(i.getName))
      .flatMap(_.getInetAddresses.toSeq.find(_.isInstanceOf[Inet4Address]))
      .get
  }

  lazy val rktBinaryPath =
    Await.result(commandExecutor(Seq("which", "rkt")), 10.seconds).trim

  private def rktCmd(rktDir: Path) = sudoCmd(rktBinaryPath, "--dir=" + rktDir)

  private def sudoCmd(xs: String*): Seq[String] = Seq("sudo", "-n", "--") ++ xs

  private def withRktDir[A](f: Path => A) = {
    val rktDir = Await.result({
      import scala.concurrent.ExecutionContext.Implicits.global
      for {
        dir <- commandExecutor(Seq("mktemp", "--tmpdir", "-d", "rkt-tmp-XXXXXX")).map(_.trim)
        _ <- commandExecutor(Seq(
          rktBinaryPath,
          s"--dir=$dir",
          "list"))
        path = Paths.get(dir)
      } yield path
    }, 1.minute)
    try {
      f(rktDir)
    } finally {
      Await.ready({
        commandExecutor(sudoCmd("rm", "-rf", rktDir.toString))
      }, 1.minute)
    }
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
