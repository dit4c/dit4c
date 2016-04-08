package extensions

import play.api.mvc.QueryStringBindable
import java.time.Instant
import java.time.format.DateTimeFormatter

object Binders {

  implicit object InstantBindable
      extends QueryStringBindable.Parsing[Instant](
          toInstant, _.toString, (_, e) => e.getMessage)

  private def toInstant(s: String): Instant = {
    Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s))
  }

}