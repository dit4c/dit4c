@(pairs: Seq[(String, String)], includeDeletions: Boolean)

@import play.twirl.api.JavaScript

@import play.api.libs.json._

@jsStr(v: String) = @{JavaScript(Json.asciiStringify(Json.toJson(v)))}

function(doc, req) {
  // All deleted documents go through by default
  @if(includeDeletions) { if (doc._deleted) return true; }
  // Check each pair in sequence
  @for((k, v) <- pairs) {
  if (doc[@jsStr(k)] != @jsStr(v)) return false;
  }
  // All pairs match, so we want this document
  return true;
}