@(pairs: Seq[(String, String)], sortKeys: Seq[String])

@import play.twirl.api.JavaScript

@import play.api.libs.json._

@js(v: Any) = { JavaScript(Json.asciiStringify(Json.toJson(v))) }

function(doc) {
  @for((k, v) <- pairs) {
  if (doc[@js(k)] != @js(v)) return;
  }
  var sortKey = @js(sortKeys)
    .map(function(v) { return doc[v]; })
    .join('-');
  emit(sortKey, null);
}