@()

function(doc) {
  if (doc.type == "Project") {
    emit(null, doc);
  }
}