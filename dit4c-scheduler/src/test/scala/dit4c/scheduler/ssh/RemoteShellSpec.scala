package dit4c.scheduler.ssh

import org.specs2.mutable.Specification
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.security._
import scala.collection.JavaConversions._
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelExec
import scala.util.Random
import java.io.ByteArrayOutputStream
import org.apache.sshd.common.keyprovider.KeyPairProvider
import com.jcraft.jsch.HostKey
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.io.BufferedWriter
import java.io.StringWriter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter

class RemoteShellSpec extends Specification {

  val keyPairs: Set[KeyPair] = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512)
    for (i <- 1.to(5)) yield kpg.genKeyPair()
  }.toSet
  def publicKeys = keyPairs.map(_.getPublic)

  "RemoteShell" >> {
    "connect to server" >> {
      pending
      /*
      val (server, publicKey) = createSshServer
      val jsch = new JSch
      val username = Random.alphanumeric.take(8).mkString
      val kp = keyPairs.head
      println(toPKCS8(kp.getPrivate.asInstanceOf[RSAPrivateKey]))
      jsch.addIdentity("id",
          kp.getPrivate.getEncoded,
          kp.getPublic.getEncoded,
          Array.emptyByteArray)
      jsch.getHostKeyRepository.add(
          new HostKey(server.getHost, publicKey.getEncoded), null)
      val session = jsch.getSession(username, server.getHost, server.getPort)
      val channel: ChannelExec =
        session.openChannel("shell").asInstanceOf[ChannelExec]
      channel.setCommand("whoami")
      val outputStream = new ByteArrayOutputStream
      channel.connect(1000)
      while (channel.isConnected()) {
        Thread.sleep(10)
      }
      println(new String(outputStream.toByteArray, "utf8"))
      */
    }
  }

  /**
   * Use Bouncy Castle to convert into PKCS#8 format that Jsch can load.
   */
  def toPKCS8(key: RSAPrivateKey) = {
    val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(
        new RSAKeyParameters(true, key.getModulus, key.getPrivateExponent))
    val writer = new StringWriter()
    (new JcaPEMWriter(writer)).writeObject(privateKeyInfo)
    writer.close
    writer.toString
  }

  def createSshServer: (SshServer, PublicKey) = {
    val server = SshServer.setUpDefaultServer()
    server.setHost("localhost")
    val (keyPairProvider, publicKey) = {
      val kpg = KeyPairGenerator.getInstance("RSA")
      kpg.initialize(512)
      val pair = kpg.genKeyPair()
      (new KeyPairProvider() {
        override val getKeyTypes =
          asJavaIterable(Seq(pair.getPrivate.getAlgorithm))
        override def loadKey(t: String) =
          if (t == pair.getPrivate.getAlgorithm) pair else null
        override val loadKeys = asJavaIterable(Seq(pair))
      }, pair.getPublic)
    }
    server.setKeyPairProvider(keyPairProvider)
    server.setPublickeyAuthenticator(
        new KeySetPublickeyAuthenticator(publicKeys))
    server.start()
    (server, publicKey)
  }

}