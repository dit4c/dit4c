@()

function(doc) {
  if (doc.type == "Key") {
    emit(null, doc);
  }
}