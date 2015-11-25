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
import org.specs2.matcher._
import org.xbill.DNS._
import org.specs2.specification.core.Fragments

class AkkaHttpExtrasSpec extends Specification with NoThrownExpectations with SequenceMatchersCreation {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  val log = Logging(system, "AkkaHttpExtrasSpec")

  import AkkaHttpExtras._

  "Akka Http Extras" should {

    val ccSettings = ClientConnectionSettings(system).copy(
          connectingTimeout = 1.second)

    "allow outgoing connections to specify InetAddress" >> {
      val host = "google.com"
      val addrs = resolve(host)

      def makeRequest(addr: InetAddress): Future[Try[StatusCode]] = {
        val c = Http().outgoingConnectionTls(addr, 443,
          None, ccSettings, None, log)
        val req = HttpRequest()
        Source.single(req).via(c)
          .runFold(Seq.empty[StatusCode])((rs, r) => rs :+ r.status)
          .map(v => Success(v.head))
          .recover({ case e => Failure(e) })
      }

      val results = Await.result(Future.sequence {
        addrs.map { addr => makeRequest(addr).map((addr,_)) }
      }, 5.seconds).toMap

      val statusTable = results.map { case (k,v) =>
        v match {
          case Success(v) => s"$k → $v"
          case Failure(e) => s"$k → $e"
        }
      }.mkString("\n")

      {
        results.filter({ case (k,v) => v.isSuccess }) must
          haveSize(beGreaterThan(results.size / 2))
      }.setMessage(statusTable +
            s"\nA majority of $host servers did not respond." +
            " Something is wrong.")
    }

    "requests that can fail over" >> {
      Seq("http","https").map { scheme =>
        scheme.toUpperCase >> {

          "implicitly" >> {
            val req = HttpRequest(uri = Uri(s"$scheme://google.com/"))
            val res = Await.result(
                Http().singleResilientRequest(req, ccSettings, None, log)
                , 30.seconds)
            res.status.isRedirection must beTrue.setMessage(
                  s"Request to ${req.uri} failed. Something is wrong.")
          }

          "explicitly" >> {
            val req = HttpRequest(uri = Uri(s"$scheme://google.com/"))
            val res = Await.result(
                Http().singleResilientRequest(req,
                  resolve(req.uri.authority.host.address),
                  ccSettings, None, log)
                , 30.seconds)
            res.status.isRedirection must beTrue.setMessage(
                  s"Request to ${req.uri} failed. Something is wrong.")
          }
        }
      }.foldLeft(Fragments.empty)(_ append _)
    }
  }

  def resolve(s: String): Seq[InetAddress] = {
    val resolver = new ExtendedResolver(Array[String]("8.8.8.8","8.8.4.4"))
    Seq(Type.AAAA,Type.A).flatMap { t =>
      val lookup = new Lookup(s, t)
      lookup.setResolver(resolver)
      lookup.run.toSeq.map {
        case r: ARecord => r.getAddress
        case r: AAAARecord => r.getAddress
      }
    }
  }


}