package dit4c.switchboard.http

import org.specs2.mutable.Specification
import java.nio.file.Files
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.execute.Result
import org.specs2.matcher.PathMatchers
import scala.concurrent.ExecutionContext.Implicits
import dit4c.switchboard.Route
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import java.net.InetSocketAddress
import akka.stream.ActorMaterializer
import akka.agent.Agent
import akka.http.scaladsl.model.headers.CustomHeader
import akka.stream.scaladsl.Source
import akka.http.ClientConnectionSettings
import akka.http.scaladsl.model.headers._
import org.specs2.ScalaCheck
import org.scalacheck.Gen
import org.scalacheck.Prop
import scala.util.Random
import org.scalacheck.Arbitrary
import java.net.InetAddress

class AuthRequestServerSpec extends Specification with PathMatchers with ScalaCheck {

  import scala.concurrent.ExecutionContext.Implicits.global
  import dit4c.common.AkkaHttpExtras._

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  "AuthRequestServer should" >> {

    val domainPartGenerator: Gen[String] = for {
        size <- Gen.chooseNum(1,63)
        part <- Gen.resize(size, Gen.identifier).map(_.toLowerCase)
      } yield part

    // Generates domain names
    val domainGenerator: Gen[String] = for {
        numParts <- Gen.chooseNum(2, 8, 3)
        parts <- Gen.containerOfN[Seq,String](numParts, domainPartGenerator)
        domain = parts.mkString(".")
      } yield domain

    // Generates IPv4 & IPv6 addresses
    val ipGenerator = for {
        numBytes <- Gen.oneOf(4, 16)
        bytes <- Gen.containerOfN[Array,Byte](numBytes,
            Arbitrary.arbByte.arbitrary)
      } yield InetAddress.getByAddress(bytes)

    val upstreamGenerator =
      for {
        scheme <- Gen.oneOf("http","https")
        host <- Gen.frequency[String](
          1 -> domainGenerator,
          10 -> ipGenerator.map(_.toString)
        )
        port <- Gen.choose[Int](1, Short.MaxValue)
        upstream = Route.Upstream(scheme, host, port)
      } yield upstream

    def routeGenerator(
        upstreams: Gen[Route.Upstream],
        baseDomains: Gen[String]) =
      for {
        baseDomain <- baseDomains
        host <- Gen.uuid.map(_.toString)
        domain = s"${host}.$baseDomain"
        serverName <- Gen.uuid.map(_.toString)
        upstream <- upstreams
        route = Route(domain, Map("X-Server-Name" -> serverName), upstream)
      } yield route

    implicit val routes: Arbitrary[Map[String,Route]] = Arbitrary {
      Gen.sized { numRoutes =>
        for {
          baseDomains <- Gen.containerOfN[Seq,String](20, domainGenerator)
          upstreams <- Gen.containerOfN[Seq,Route.Upstream](20,
              upstreamGenerator)
          routes <- Gen.containerOfN[Set,Route](numRoutes,
              routeGenerator(Gen.oneOf(upstreams), Gen.oneOf(baseDomains)))
          routeMap = routes.toStream.map(v => (v.domain,v)).toMap
        } yield routeMap
      }
    }

    "return upstream based on Host header" ! prop { (routes: Map[String,Route]) =>
      val serverInstance = Await.result(
          AuthRequestServer.start(Agent(routes)), 5.seconds)

      val testRoute = Random.shuffle(routes.values).head
      val req = HttpRequest().withHeaders(Host(Uri.Host(testRoute.domain)))

      val res = Await.result({
        val c = Http().outgoingConnection(
          serverInstance.socket.getAddress,
          serverInstance.socket.getPort,
          None, ClientConnectionSettings(system), system.log)
        val p = Promise[HttpResponse]()
        Source.single(req).via(c)
          .runForeach((r) => p.trySuccess(r))
          .onFailure({ case e: Throwable => p.tryFailure(e) })
        p.future
      }, 500.milliseconds)

      res.status must_== StatusCodes.OK
      res.headers must contain(
          RawHeader("X-Target-Upstream", testRoute.upstream.toString))

      Await.ready(serverInstance.shutdown(), 5.seconds)
      done
    }.set(minTestsOk = 10, minSize = 1, maxSize = 100000)
  }
}