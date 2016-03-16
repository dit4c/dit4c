@()

function(doc) {
  if (doc.type == "AccessToken") {
    emit(doc.resource.id, null);
  }
}