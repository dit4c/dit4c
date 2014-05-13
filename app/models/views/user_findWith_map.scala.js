@(identity: String)

function(doc) {
  if (doc.identities.indexOf("@identity") != -1) {
    emit(null, doc);
  }
}