package dit4c.common

import java.net._
import akka.http.scaladsl._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.ClientConnectionSettings
import akka.stream.scaladsl.Flow
import scala.concurrent._
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.headers.Host
import akka.stream.io._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.OutgoingConnection
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Keep
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import scala.concurrent.duration.FiniteDuration

object AkkaHttpExtras {

  implicit class Extras(http: HttpExt)(implicit system: ActorSystem) {

    def singleResilientRequest(request: HttpRequest,
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsContext],
        log: LoggingAdapter)(implicit fm: Materializer): Future[HttpResponse] =
      singleResilientRequest(request,
          request.uri.authority.host.inetAddresses,
          settings, httpsContext, log)

    def singleResilientRequest(request: HttpRequest,
        addrs: Seq[InetAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsContext],
        log: LoggingAdapter)(implicit fm: Materializer): Future[HttpResponse] = {
      implicit val ec = fm.executionContext
      val addr::remainingAddrs = addrs
      val c = outgoingConnectionImpl(addr, request.uri.effectivePort,
          None, settings,
          httpsContext orElse {
            if (request.uri.scheme == "https") Some(http.defaultClientHttpsContext)
            else None
          }, log)
      val p = Promise[HttpResponse]()
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
      p.future
        .recoverWith {
          case e: akka.stream.StreamTcpException if !remainingAddrs.isEmpty =>
            log.warning(s"Request to $addr timed out. " +
                s"Trying remaining ${remainingAddrs.size} addresses.")
            singleResilientRequest(request,
                remainingAddrs, settings, httpsContext, log)
        }
    }

    def outgoingConnection(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
      outgoingConnectionImpl(addr, port, localAddress, settings, None, log)


    def outgoingConnectionTls(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsContext],
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
      outgoingConnectionImpl(addr, port, localAddress, settings,
          httpsContext orElse Some(http.defaultClientHttpsContext), log)

    private def outgoingConnectionImpl(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsContext],
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = {
      val layer = {
        val hostHeader = port match {
          case 80 if httpsContext.isEmpty => Host(addr.getHostName)
          case 443 if httpsContext.isDefined => Host(addr.getHostName)
          case _ => Host(addr.getHostName, port)
        }
        http.clientLayer(hostHeader, settings, log)
      }
      val tlsStage = httpsContext match {
        case Some(hctx) => SslTls(hctx.sslContext, hctx.firstSession, Client,
            hostInfo = Some(addr.getHostName -> port))
        case None => SslTlsPlacebo.forScala
      }
      val transportFlow = Tcp().outgoingConnection(
          new InetSocketAddress(addr, port), localAddress,
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
