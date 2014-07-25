package testing

import play.api.test.FakeApplication

object TestUtils {

  def fakeApp = FakeApplication(
      additionalConfiguration = Map(
          "keys.manage" -> false,
          "keys.length" -> 512,
          "couchdb.testing" -> true,
          "rapidaaf" -> Map(
              "id"   -> "RapidAAF",
              "url"  -> "http://example.test/",
              "key"  -> "testkey"
          )
      ))
}