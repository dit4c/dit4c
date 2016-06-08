package dit4c.scheduler

import akka.http.scaladsl.server.Directives
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.actor.ActorSystem
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.HasActorSystem
import akka.stream.ActorMaterializer
import scala.reflect.runtime.universe
import com.github.swagger.akka.model.Info
import com.github.swagger.akka.model.`package`.License

package object routes extends Directives with PlayJsonSupport {

  def apiDocsRoutes(system: ActorSystem, hostAndPort: String) =
    pathPrefix("api-docs") {
      get {
        getFromResourceDirectory("META-INF/resources/webjars/swagger-ui/2.1.4")
      } ~
      pathSingleSlash {
        get {
          redirect("index.html?url=/api-docs/swagger.json",
              StatusCodes.PermanentRedirect)
        }
      }
    } ~ (new SwaggerDocs(system, hostAndPort)).routes

  class SwaggerDocs(system: ActorSystem, hostAndPort: String)
      extends SwaggerHttpService with HasActorSystem {

    override implicit val actorSystem: ActorSystem = system
    override implicit val materializer: ActorMaterializer = ActorMaterializer()
    override val apiTypes = Seq(universe.typeOf[ZoneRoutes])
    override val host = hostAndPort
    override val basePath = "/"
    override val apiDocsPath = "api-docs"
    override val info = Info(license = Some(
        License("MIT", "https://github.com/dit4c/dit4c/blob/master/COPYING")))

  }

}