@()

function(doc) {
  if (doc.type == "Container") {
    emit(null, doc);
  }
}