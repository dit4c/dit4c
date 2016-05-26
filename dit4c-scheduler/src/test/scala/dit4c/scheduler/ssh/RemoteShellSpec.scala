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
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.io.BufferedWriter
import java.io.StringWriter
import org.bouncycastle.util.encoders.Base64
import com.jcraft.jsch.ChannelShell
import java.io.ByteArrayInputStream
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.server.shell.InvertedShellWrapper
import org.apache.sshd.server.shell.ProcessShell
import org.apache.sshd.server.CommandFactory

class RemoteShellSpec extends Specification {

  val keyPairs: Set[KeyPair] = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(512)
    for (i <- 1.to(5)) yield kpg.genKeyPair()
  }.toSet
  def publicKeys = keyPairs.map(_.getPublic)

  "RemoteShell" >> {
    "connect to server" >> {
      val (server, publicKey) = createSshServer
      val jsch = new JSch
      val username = Random.alphanumeric.take(8).mkString
      val kp = keyPairs.head
      jsch.addIdentity("id",
          toOpenSshPrivateKey(
            kp.getPrivate.asInstanceOf[RSAPrivateKey],
            kp.getPublic.asInstanceOf[RSAPublicKey]).getBytes,
          toOpenSshPublicKey(kp.getPublic.asInstanceOf[RSAPublicKey]),
          null)
      jsch.getHostKeyRepository.add(
          new HostKey(
              server.getHost,
              toOpenSshPublicKey(publicKey.asInstanceOf[RSAPublicKey])),
              null)
      val session = jsch.getSession(username, server.getHost, server.getPort)
      session.connect()
      val channel: ChannelExec =
        session.openChannel("exec").asInstanceOf[ChannelExec]
      channel.setCommand("whoami")
      val outputStream = new ByteArrayOutputStream
      channel.setOutputStream(outputStream)
      channel.connect(1000)
      while (channel.isConnected()) {
        Thread.sleep(10)
      }
      new String(outputStream.toByteArray, "utf8").trim === System.getProperty("user.name")
    }
  }

  /**
   * Use Bouncy Castle to convert into PKCS#8 format that Jsch can load.
   */
  def toOpenSshPrivateKey(priv: RSAPrivateKey, pub: RSAPublicKey): String = {
    import org.bouncycastle.openssl.jcajce.JcaPEMWriter
    import org.bouncycastle.openssl.MiscPEMGenerator
    import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
    import org.bouncycastle.asn1.x509.AlgorithmIdentifier
    import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
    import org.bouncycastle.asn1.pkcs.{RSAPrivateKey => ASN1RSAPrivateKey}
    val asn1Key: ASN1RSAPrivateKey = {
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

}