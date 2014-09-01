@(resourceId: String)

function(doc) {
  if (doc.type == "AccessToken" && doc.resource.id == "@resourceId") {
    emit(null, doc);
  }
}