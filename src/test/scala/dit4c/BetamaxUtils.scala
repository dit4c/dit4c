package dit4c

import co.freeside.betamax.Recorder
import co.freeside.betamax.proxy.jetty.ProxyServer

object BetamaxUtils {

  def withTape[A](tapeName: String)(f: => A) = {
    val recorder = new Recorder
    val proxyServer = new ProxyServer(recorder)
    recorder.insertTape(tapeName)
    try {
      proxyServer.start()
      // Handle Spray.IO nonProxyHosts check in scala.can.client.ProxySettings
      // (essentially, you can't have nothing)
      System.setProperty("http.nonProxyHosts", "1.1.1.1")
      f
    } finally {
      recorder.ejectTape()
      proxyServer.stop()
    }
  }

}