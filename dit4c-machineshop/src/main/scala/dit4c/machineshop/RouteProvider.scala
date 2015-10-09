package dit4c.machineshop

import akka.http.scaladsl.server.Route

trait RouteProvider {

  def route: Route

}