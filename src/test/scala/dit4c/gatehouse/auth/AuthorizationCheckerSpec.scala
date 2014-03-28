package dit4c.gatehouse.auth

import org.specs2.mutable.Specification

class AuthorizationCheckerSpec extends Specification {

  val checker = new AuthorizationChecker

  // Token includes claim:
  // "http://dit4c.github.io/authorized_containers": ["foo", "bar"]
  val testToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwOi8vZGl0NGMuZ2l0aHViLmlvLyIsInN1YiI6Im1haWx0bzp0LmRldHRyaWNrQHVxLmVkdS5hdSIsImlhdCI6MTM5NTk2ODYyNywgImh0dHA6Ly9kaXQ0Yy5naXRodWIuaW8vYXV0aG9yaXplZF9jb250YWluZXJzIjogWyJmb28iLCAiYmFyIl0gfQ."

  // Token includes malformed claim:
  // "http://dit4c.github.io/authorized_containers": "foo"
  val malformedToken = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwOi8vZGl0NGMuZ2l0aHViLmlvLyIsInN1YiI6Im1haWx0bzp0LmRldHRyaWNrQHVxLmVkdS5hdSIsImlhdCI6MTM5NTk2ODYyNywgImh0dHA6Ly9kaXQ0Yy5naXRodWIuaW8vYXV0aG9yaXplZF9jb250YWluZXJzIjogImZvbyIgfQ."

  "Authorization Checker" should {

    "return true only for containers authorized in the token" in {
      checker("foo")(testToken) must beTrue
      checker("bar")(testToken) must beTrue
      checker("baz")(testToken) must beFalse
    }

    "return false for malformed tokens" in {
      checker("foo")(malformedToken) must beFalse
    }
  }
}


