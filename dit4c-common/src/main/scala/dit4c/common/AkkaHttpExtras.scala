package dit4c.common

import java.net._
import akka.http.scaladsl._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.ClientConnectionSettings
import akka.stream.scaladsl.Flow
import scala.concurrent.Future
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.headers.Host
import akka.stream.io._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.OutgoingConnection
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Keep
import akka.stream.Materializer
import akka.stream.scaladsl.Source

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
      val c = outgoingConnectionTls(addrs.head, request.uri.effectivePort,
          None, settings,
          httpsContext orElse {
            if (request.uri.scheme == "https") Some(http.defaultClientHttpsContext)
            else None
          }, log)
      Source.single(request).via(c)
        .runFold(Seq.empty[HttpResponse])((rs, r) => rs :+ r)
        .map(_.head)
        .recoverWith {
          case e: akka.stream.StreamTcpException if addrs.size > 1 =>
            singleResilientRequest(request,
                addrs.tail, settings, httpsContext, log)
        }
    }

    def outgoingConnectionTls(addr: InetAddress, port: Int,
        localAddress: Option[InetSocketAddress],
        settings: ClientConnectionSettings,
        httpsContext: Option[HttpsContext],
        log: LoggingAdapter): Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] =
      outgoingConnection(addr, port, localAddress, settings,
          httpsContext orElse Some(http.defaultClientHttpsContext), log)

    private def outgoingConnection(addr: InetAddress, port: Int,
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