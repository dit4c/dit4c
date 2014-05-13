@(identity: String)

function(doc) {
  if (doc.type == "User" && doc.identities.indexOf("@identity") != -1) {
    emit(null, doc);
  }
}