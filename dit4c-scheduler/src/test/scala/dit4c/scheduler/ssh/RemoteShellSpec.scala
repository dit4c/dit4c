package dit4c.scheduler.ssh

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

import scala.collection.JavaConversions.asJavaIterable
import scala.collection.JavaConversions.setAsJavaSet
import scala.concurrent.duration.DurationInt
import scala.util.Random

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.CommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification

import dit4c.scheduler.runner.CommandExecutor
import dit4c.scheduler.runner.CommandExecutorHelper
import org.specs2.execute.AsResult
import org.specs2.specification.ForEach
import java.nio.file.Files
import scala.concurrent.Await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.specs2.matcher.FileMatchers
import java.io.FileInputStream
import scala.sys.process.BasicIO

class RemoteShellSpec(implicit ee: ExecutionEnv) extends Specification
    with ForEach[CommandExecutor] with FileMatchers {

  val keyPairs: Set[KeyPair] = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512)
    for (i <- 1.to(5)) yield kpg.genKeyPair()
  }.toSet
  def publicKeys = keyPairs.map(_.getPublic)

  override def foreach[R: AsResult](f: CommandExecutor => R) = {
    val kp = keyPairs.head
    val (server, hostPublicKey) = createSshServer
    val username = Random.alphanumeric.take(8).mkString
    val ce: CommandExecutor = RemoteShell(server.getHost,
      server.getPort,
      username: String,
      kp.getPrivate.asInstanceOf[RSAPrivateKey],
      kp.getPublic.asInstanceOf[RSAPublicKey],
      hostPublicKey.asInstanceOf[RSAPublicKey])
    AsResult(f(ce))
  }

  "RemoteShell" >> {
    "can handle single commands" >> { ce: CommandExecutor =>
      ce(Seq("whoami")).map(_.trim) must {
        be_==(System.getProperty("user.name"))
      }.awaitFor(1.minute)
    }

    "can handle commands with arguments" >> { ce: CommandExecutor =>
      ce(Seq("echo", "Hello World!")).map(_.trim) must {
        be_==("Hello World!")
      }.awaitFor(1.minute)
    }

    "can create files" >> { ce: CommandExecutor =>
      val tmpDir = Files.createTempDirectory("remote-shell-test-")
      val tmpFile = tmpDir.resolve("test.txt").toAbsolutePath
      val inBytes = "Hello World!\n".getBytes
      try {
        Await.ready(ce(
            Seq("sh", "-c", s"cat - > ${tmpFile}"),
            new ByteArrayInputStream(inBytes)), 1.minute);
        { tmpFile.toString must beAFilePath } and
        { readFileBytes(tmpFile) must_== inBytes }
      } finally {
        Files.deleteIfExists(tmpFile)
        Files.delete(tmpDir)
      }
    }

    "exits with non-zero on error" >> { ce: CommandExecutor =>
      ce(Seq("doesnotexist")) must {
        throwAn[Exception].like {
          case e => e.getMessage must contain("doesnotexist: command not found")
        }
      }.awaitFor(1.minute)
    }

  }

  def createSshServer: (SshServer, PublicKey) = {
    val server = SshServer.setUpDefaultServer()
    server.setHost("localhost")
    val (keyPairProvider, publicKey) = {
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(512)
      val pair = kpg.genKeyPair()
      (new KeyPairProvider() {
        private val keyType = KeyPairProvider.SSH_RSA
        override val getKeyTypes = asJavaIterable(Seq(keyType))
        override def loadKey(t: String) =
          if (t == keyType) pair else null
        override val loadKeys = asJavaIterable(Seq(pair))
      }, pair.getPublic)
    }
    server.setKeyPairProvider(keyPairProvider)
    server.setPublickeyAuthenticator(
        new KeySetPublickeyAuthenticator(publicKeys))
    server.setCommandFactory(new CommandFactory() {
      // Generic command factory that just passes the command to a shell
      def createCommand(command: String) =
        new InvertedShellWrapper(new ProcessShell(command))
    })
    server.start()
    (server, publicKey)
  }

  def readFileBytes(file: Path): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    BasicIO.transferFully(new FileInputStream(file.toFile), out)
    out.toByteArray
  }

}