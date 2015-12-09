package dit4c.switchboard

import org.specs2.ScalaCheck
import org.specs2.matcher.PathMatchers
import org.specs2.mutable.Specification
import java.net.InetAddress

class RouteSpec extends Specification with PathMatchers with ScalaCheck {

  "Route.Upstream" >> {

    "toString with valid URI" >> {

      "for hostnames" >> {
        Route.Upstream("https", "example.test", 8080).toString must_==
          "https://example.test:8080"
      }

      "for IPv4 addresses" >> {
        Route.Upstream("http", "127.0.0.1", 8090).toString must_==
          "http://127.0.0.1:8090"
      }

      "for IPv6 addresses" >> {
        Route.Upstream("http", "::1", 9000).toString must_== "http://[::1]:9000"
      }
    }
  }
}