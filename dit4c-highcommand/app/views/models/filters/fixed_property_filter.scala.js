@(pairs: Seq[(String, String)])

@import play.twirl.api.JavaScript

@import play.api.libs.json._

@js(v: Any) = { JavaScript(Json.asciiStringify(Json.toJson(v))) }

function(doc, req) {
  // All deleted documents go through by default
  if (doc._deleted) return true;
  // Check each pair in sequence
  @for((k, v) <- pairs) {
  if (doc[@js(k)] != @js(v)) return false;
  }
  // All pairs match, so we want this document
  return true;
}