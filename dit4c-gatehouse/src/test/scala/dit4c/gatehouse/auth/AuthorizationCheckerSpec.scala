package dit4c.gatehouse.auth

import org.specs2.mutable.Specification

class AuthorizationCheckerSpec extends Specification {

  val checker = new AuthorizationChecker

  import AuthorizationCheckerSpecTokens._

  "Authorization Checker" should {

    "return true only for containers authorized in the token" in {
      checker(testToken, "foo") must beRight
      checker(testToken, "bar") must beRight
      checker(testToken, "baz") must beLeft
    }

    "return false for malformed tokens" in {
      checker(malformedToken, "foo") must beLeft
    }

    "return false for malformed JSON" in {
      checker("null"+testToken, "foo") must beLeft
    }
  }
}

object AuthorizationCheckerSpecTokens {

  // Token includes claim:
  // "http://dit4c.github.io/authorized_containers": ["foo", "bar", "die"]
  val testToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwOi8vZGl0NGMuZ2l0aHViLmlvLyIsInN1YiI6Im1haWx0bzp0LmRldHRyaWNrQHVxLmVkdS5hdSIsImlhdCI6MTM5NTk2ODYyNywgImh0dHA6Ly9kaXQ0Yy5naXRodWIuaW8vYXV0aG9yaXplZF9jb250YWluZXJzIjogWyJmb28iLCAiYmFyIiwgImRpZSJdIH0."

  // Token includes malformed claim:
  // "http://dit4c.github.io/authorized_containers": "foo"
  val malformedToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwOi8vZGl0NGMuZ2l0aHViLmlvLyIsInN1YiI6Im1haWx0bzp0LmRldHRyaWNrQHVxLmVkdS5hdSIsImlhdCI6MTM5NTk2ODYyNywgImh0dHA6Ly9kaXQ0Yy5naXRodWIuaW8vYXV0aG9yaXplZF9jb250YWluZXJzIjogImZvbyIgfQ."

}


