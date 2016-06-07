package dit4c.scheduler.ssh

import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

import dit4c.scheduler.runner.CommandExecutor
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream

object RemoteShell {

  implicit val executionContext = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool())

  def apply(
      host: String,
      port: Int,
      username: String,
      userPrivateKey: RSAPrivateKey,
      userPublicKey: RSAPublicKey,
      hostPublicKey: RSAPublicKey): CommandExecutor = {
    val jsch = new JSch
    val username = Random.alphanumeric.take(8).mkString
    jsch.addIdentity("id",
        toOpenSshPrivateKey(userPrivateKey, userPublicKey).getBytes,
        toOpenSshPublicKey(userPublicKey),
        null)
    jsch.getHostKeyRepository.add(
        new HostKey(
            host,
            RemoteShell.toOpenSshPublicKey(hostPublicKey)),
            null);
    var lastSession: Option[Session] = None
    ce {
      if (lastSession.isDefined && lastSession.get.isConnected)
        Future.successful(lastSession.get)
      else Future {
        val session = jsch.getSession(username, host, port)
        session.connect()
        lastSession = Some(session)
        session
      }
    }
  }

  protected def ce(sessionProvider: => Future[Session]): CommandExecutor =
    (cmd: Seq[String], in: InputStream, out: OutputStream, err: OutputStream) =>
      sessionProvider.map { session =>
        val channel: ChannelExec =
          session.openChannel("exec").asInstanceOf[ChannelExec]
        val cmdLine = escapeAndJoin(cmd)
        channel.setCommand("bash")
        channel.setInputStream(
          new SequenceInputStream(
            new ByteArrayInputStream(("exec "+cmdLine+"\n").getBytes),
            in))
        channel.setOutputStream(out)
        channel.setErrStream(err)
        channel.connect(1000)
        while (channel.isConnected) {
          Thread.sleep(10)
        }
        channel.getExitStatus
      }

  protected def escapeAndJoin(cmd: Seq[String]): String =
    cmd.map(_.flatMap(escape)).mkString(" ")

  /**
   * Escape normal characters and strip out control characters.
   */
  protected def escape(c: Char): Seq[Char] = c match {
    case c if c <= '\u001f' || c == '\u001f' => Seq.empty
    case c => Seq('\\', c)
  }

  def toOpenSshPrivateKey(priv: RSAPrivateKey, pub: RSAPublicKey): String = {
    import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
    import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
    import org.bouncycastle.asn1.pkcs.{ RSAPrivateKey => ASN1RSAPrivateKey }
    import org.bouncycastle.asn1.x509.AlgorithmIdentifier
    import org.bouncycastle.openssl.MiscPEMGenerator
    import org.bouncycastle.openssl.jcajce.JcaPEMWriter
    val asn1Key: ASN1RSAPrivateKey = {
      import scala.language.implicitConversions
      implicit def bigIntToBigInteger(bi: BigInt) = bi.bigInteger
      val v = RsaFactorizer(
        pub.getModulus, pub.getPublicExponent, priv.getPrivateExponent)
      new ASN1RSAPrivateKey(v._1, v._2, v._3, v._4, v._5, v._6, v._7, v._8)
    }
    val keyInfo = new PrivateKeyInfo(
      new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption),
      asn1Key)
    val writer = new StringWriter()
    val pemWriter = new JcaPEMWriter(writer)
    pemWriter.writeObject(new MiscPEMGenerator(keyInfo))
    pemWriter.close
    writer.close
    writer.toString
  }

  def toOpenSshPublicKey(pub: RSAPublicKey): Array[Byte] = {
    import java.nio.ByteBuffer
    // As per RFC4251, string/mpint are represented by uint32 length then bytes
    def lengthThenBytes(bs: Array[Byte]): Array[Byte] =
      ByteBuffer.allocate(4).putInt(bs.length).array() ++ bs
    lengthThenBytes("ssh-rsa".getBytes("us-ascii")) ++
        lengthThenBytes(pub.getPublicExponent.toByteArray) ++
        lengthThenBytes(pub.getModulus.toByteArray)
  }


}