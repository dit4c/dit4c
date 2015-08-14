package dit4c.machineshop.docker.models

class ContainerLink(outside: String, inside: String) {
  override lazy val toString = outside + ":" + inside
}

object ContainerLink {
  def apply(s: String) = s.split(':') match {
    case Array(outside, inside) => new ContainerLink(outside, inside)
    case other =>
      throw new Exception(s"$s is not a valid container link")
  }
}