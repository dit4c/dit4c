package dit4c.common

import java.net._
import akka.http.scaladsl._
import akka.stream.scaladsl._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ClientConnectionSettings
import scala.concurrent._
import akka.stream.Client
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.headers.Host
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.OutgoingConnection
import akka.stream.Materializer
import scala.concurrent.duration.FiniteDuration
import akka.http.scaladsl.HttpsConnectionContext
import javax.net.ssl.SSLContext

object AkkaHttpExtras {

  val modernHttpsConnectionContext = new HttpsConnectionContext(
        SSLContext.getDefault,
        enabledProtocols = Some("TLSv1.2" :: "TLSv1.1" :: Nil))

  implicit class Extras(http: HttpExt)(implicit system: ActorSystem) {

    def singleResilientRequest(request: HttpRequest,
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsConnectionContext],
        log: LoggingAdapter)(implicit fm: Materializer): Future[HttpResponse] =
      singleResilientRequest(request,
          request.uri.authority.host.inetAddresses,
          settings, httpsContext, log)

    def singleResilientRequest(request: HttpRequest,
        addrs: Seq[InetAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsConnectionContext],
        log: LoggingAdapter)(implicit fm: Materializer): Future[HttpResponse] = {
      implicit val ec = fm.executionContext
      val addr::remainingAddrs = addrs
      val c = outgoingConnectionImpl(addr, request.uri.authority.port,
          None, settings,
          httpsContext orElse {
            if (request.uri.scheme == "https") Some(http.defaultClientHttpsContext)
            else None
          }, log)
      val p = Promise[HttpResponse]()
      // Result must be made recursive before resolution attempts are made, or
      // else a short-circuit is possible.
      val fResult = p.future
        .recoverWith {
          case e: akka.stream.StreamTcpException if !remainingAddrs.isEmpty =>
            log.warning(s"Request to $addr failed. " +
                s"Trying remaining ${remainingAddrs.size} addresses.")
            singleResilientRequest(request,
                remainingAddrs, settings, httpsContext, log)
        }
      settings.idleTimeout match {
        case timeout: FiniteDuration =>
          fm.scheduleOnce(timeout, new Runnable() {
            override def run() {
              p.tryFailure(new TimeoutException(s"No response within $timeout"))
            }
          })
        case _ => // No timeout
      }
      Source.single(request).via(c)
        .runForeach((r) => p.trySuccess(r))
        .onFailure({ case e: Throwable => p.tryFailure(e) })
      fResult
    }

    def outgoingConnection(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
      outgoingConnectionImpl(addr, port, localAddress, settings, None, log)


    def outgoingConnectionTls(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsConnectionContext],
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
      outgoingConnectionImpl(addr, port, localAddress, settings,
          httpsContext orElse Some(http.defaultClientHttpsContext), log)

    private def outgoingConnectionImpl(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsConnectionContext],
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      val host = addr.getHostName.stripSuffix(".")
      val effectivePort = port match {
        case 0 if httpsContext.isEmpty => 80
        case 0 => 443
        case _ => port
      }
      val layer = {
        val hostHeader = effectivePort match {
          case 80 if httpsContext.isEmpty => Host(host)
          case 443 if httpsContext.isDefined => Host(host)
          case _ => Host(host, port)
        }
        http.clientLayer(hostHeader, settings, log)
      }
      val tlsStage = httpsContext match {
        case Some(hctx) =>
          TLS(hctx.sslContext, hctx.firstSession, Client,
              hostInfo = Some(host -> effectivePort))
        case None => TLSPlacebo()
      }
      val transportFlow = Tcp().outgoingConnection(
          new InetSocketAddress(addr, effectivePort), localAddress,
          settings.socketOptions, halfClose = true,
          settings.connectingTimeout, settings.idleTimeout)
      val tmp = tlsStage.joinMat(transportFlow) { (_, f) =>
        import system.dispatcher
        f.map { c => OutgoingConnection(c.localAddress, c.remoteAddress) }
      }
      layer.joinMat(tmp)(Keep.right)
    }

  }
}
