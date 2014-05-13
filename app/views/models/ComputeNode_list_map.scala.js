@()

function(doc) {
  if (doc.type == "ComputeNode") {
    emit(null, doc);
  }
}