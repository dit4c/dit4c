package testing

import play.api.test.FakeApplication
import scala.util.Random

object TestUtils {

  def fakeApp = FakeApplication(
      additionalConfiguration = Map(
          "application.baseUrl" -> "http://localhost.localdomain/",
          "keys.manage" -> false,
          "keys.length" -> 512,
          "couchdb.testing" -> true,
          "rapidaaf" -> Map(
              "id"   -> "RapidAAF",
              "url"  -> "http://example.test/",
              "key"  -> Random.nextString(32)
          )
      ))
}