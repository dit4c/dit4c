package dit4c.common

import org.specs2.mutable.Specification
import java.net.InetAddress
import scala.collection.JavaConversions._
import java.net.Inet4Address
import akka.http.scaladsl.Http
import akka.actor.ActorSystem
import akka.http.ClientConnectionSettings
import akka.event.Logging
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.Source
import scala.concurrent.Promise
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorMaterializer
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.http.scaladsl.model._
import scala.concurrent.Future
import scala.util._

class AkkaHttpExtrasSpec extends Specification {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  val log = Logging(system, "AkkaHttpExtrasSpec")

  import AkkaHttpExtras._

  "Akka Http Extras" should {

    "allow outgoing connections to specify InetAddress" >> {
      val host = "google.com"
      val addrs = InetAddress.getAllByName(host).toSeq.sortBy {
        case _: Inet4Address => 1
        case _ => 0
      }

      def makeRequest(addr: InetAddress): Future[Try[StatusCode]] = {
        val c = Http().outgoingConnectionTls(addr, 443,
          None, ClientConnectionSettings(system), None, log)
        val req = HttpRequest()
        Source.single(req).via(c)
          .runFold(Seq.empty[StatusCode])((rs, r) => rs :+ r.status)
          .map(v => Success(v.head))
          .recover({ case e => Failure(e) })
      }

      val results = Await.result(Future.sequence {
        addrs.map { addr => makeRequest(addr).map((addr,_)) }
      }, 5.seconds).toMap

      results.foreach { case (k,v) =>
        log.debug(v match {
          case Success(v) => s"$k → $v"
          case Failure(e) => s"$k → $e"
        })
      }

      results.filter({ case (k,v) => v.isSuccess }).size must
        beGreaterThan(results.size / 2).setMessage(
            s"A majority of $host servers did not respond. Something is wrong.")
    }

    "allow requests that can fail over" >> {

      "implicitly" >> {
        val req = HttpRequest(uri = Uri("https://google.com/"))
        val res = Await.result(
            Http().singleResilientRequest(req,
                ClientConnectionSettings(system), None, log)
            , 5.seconds)

        res.status must beEqualTo(StatusCodes.Found).setMessage(
              s"Request to ${req.uri} failed. Something is wrong.")
      }

      "explicitly" >> {
        val req = HttpRequest(uri = Uri("https://google.com/"))
        val res = Await.result(
            Http().singleResilientRequest(req,
              req.uri.authority.host.inetAddresses.sortBy {
                case _: Inet4Address => 1
                case _ => 0
              },
              ClientConnectionSettings(system), None, log)
            , 5.seconds)
        res.status must beEqualTo(StatusCodes.Found).setMessage(
              s"Request to ${req.uri} failed. Something is wrong.")
      }
    }
  }


}