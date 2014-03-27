package dit4c.gatehouse

import spray.util.LoggingContext
import spray.routing._
import spray.http._
import spray.json._
import MediaTypes._

trait AuthService extends HttpService {

  val authRoute =
    path("auth") {
      get {
        respondWithMediaType(`text/plain`) {
          complete {
            "test"
          }
        }
      }
    }

}