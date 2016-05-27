package dit4c.scheduler.runner

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
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

class RktRunnerSpec(implicit ee: ExecutionEnv)
    extends Specification with ScalaCheck with MatcherMacros {

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

  "RktRunner" >> {

    "list" >> {

      "initially return empty" >> {
        import scala.concurrent.ExecutionContext.Implicits.global
        val rkt = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        rkt.list must beEmpty[Set[RktPod]].awaitFor(10.seconds)
      }

      "should show exited pods" >> {
        import scala.language.experimental.macros
        import scala.concurrent.ExecutionContext.Implicits.global
        val rkt = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        val rktCmd = Seq("sudo", "-n",
          Process(s"which rkt").lineStream.mkString.trim,
          s" --dir=${rkt.dir}").mkString(" ")
        // Run a pod
        Process(s"$rktCmd run --insecure-options=image --net=none $testImage --exec /bin/true").!!
        // Check listing
        rkt.list must {
          haveSize[Set[RktPod]](1) and contain(
            matchA[RktPod]
              .uuid(not(beEmpty[String]))
              .state(be(RktPod.States.Exited))
          )
        }.awaitFor(1.minute)
      }

      "should show prepared pods" >> {
        import scala.language.experimental.macros
        import scala.concurrent.ExecutionContext.Implicits.global
        val rkt = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        val rktCmd = Seq("sudo", "-n",
          Process(s"which rkt").lineStream.mkString.trim,
          s" --dir=${rkt.dir}").mkString(" ")
        // Prepared a pod
        Process(s"$rktCmd prepare --insecure-options=image $testImage --exec /bin/true").!!
        // Check listing
        rkt.list must {
          haveSize[Set[RktPod]](1) and contain(
            matchA[RktPod]
              .uuid(not(beEmpty[String]))
              .state(be(RktPod.States.Prepared))
          )
        }.awaitFor(1.minute)
      }

      "should show running pods" >> {
        import scala.language.experimental.macros
        import scala.concurrent.ExecutionContext.Implicits.global
        val rkt = new RktRunner(
            LocalCommandExecutor.apply,
            Files.createTempDirectory("rkt-tmp"))
        val rktCmd = Seq("sudo", "-n",
          Process(s"which rkt").lineStream.mkString.trim,
          s" --dir=${rkt.dir}").mkString(" ")
        // Run a pod
        val runOutput = new ByteArrayOutputStream()
        val p = Promise[Int]()
        val toProc = new InputStream() {
          override def read = Await.result(p.future, 2.minutes)
        }
        val readyToken = Random.alphanumeric.take(40).mkString
        val proc = Future {
          Process(s"$rktCmd run --interactive  --insecure-options=image --net=none $testImage --exec /bin/sh -- -c 'echo $readyToken; cat'")
            .#<(toProc)
            .#>(runOutput)
            .!!
        }
        // Wait for the pod to start
        while (!runOutput.toByteArray.containsSlice(readyToken.getBytes)) {
          Thread.sleep(100)
        }
        // Check listing
        try {
          rkt.list must {
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

    }

  }


  /**
   * Runs all commands locally
   */
  object LocalCommandExecutor {

    implicit val executionContext = ExecutionContext.fromExecutorService(
        Executors.newCachedThreadPool())

    def apply(
        cmd: String,
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