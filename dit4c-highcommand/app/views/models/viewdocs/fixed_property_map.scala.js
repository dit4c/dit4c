@(pairs: Seq[(String, String)], sortKeys: Seq[String])

@import play.twirl.api.JavaScript

@import play.api.libs.json._

@jsStr(v: String) = @{JavaScript(Json.asciiStringify(Json.toJson(v)))}

@jsArr(v: Seq[String]) = @{JavaScript(Json.asciiStringify(Json.toJson(v)))}

function(doc) {
  @for((k, v) <- pairs) {
  if (doc[@jsStr(k)] != @jsStr(v)) return;
  }
  var sortKey = @jsArr(sortKeys)
    .map(function(v) { return doc[v]; })
    .join('-');
  emit(sortKey, null);
}