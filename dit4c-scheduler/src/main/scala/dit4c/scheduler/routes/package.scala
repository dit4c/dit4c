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

package object routes extends Directives with PlayJsonSupport {

  def swaggerRoutes(system: ActorSystem, hostAndPort: String) =
    pathPrefix("swagger") {
      get {
        getFromResourceDirectory("META-INF/resources/webjars/swagger-ui/2.1.4")
      } ~
      pathSingleSlash(get(redirect("index.html", StatusCodes.PermanentRedirect)))
    } ~ (new SwaggerDocs(system, hostAndPort)).routes

  class SwaggerDocs(system: ActorSystem, hostAndPort: String)
      extends SwaggerHttpService with HasActorSystem {

    override implicit val actorSystem: ActorSystem = system
    override implicit val materializer: ActorMaterializer = ActorMaterializer()
    override val apiTypes = Seq(universe.typeOf[ZoneRoutes])
    override val host = hostAndPort //the url of your api, not swagger's json endpoint
    override val basePath = "/"    //the basePath for the API you are exposing
    override val apiDocsPath = "swagger" //where you want the swagger-json endpoint exposed
    override val info = Info() //provides license and other description details

  }

}