@(pairs: Seq[(String, String)], sortKeys: Seq[String])

@import play.twirl.api.JavaScript

@import play.api.libs.json._

function(doc) {
  @for((k, v) <- pairs) {
	 if (doc['@k'] != '@v') return;
  }
  var sortKey = @JavaScript(JsArray(sortKeys.map(JsString(_))).toString)
    .map(function(v) { return doc[v]; })
    .join('-');
  emit(sortKey, null);
}