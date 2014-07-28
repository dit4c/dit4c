package dit4c.machineshop

import spray.routing.RequestContext

trait RouteProvider {

  def route: RequestContext => Unit

}