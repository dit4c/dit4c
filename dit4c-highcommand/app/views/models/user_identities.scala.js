@()

function(doc) {
  if (doc.type == "User") {
    doc.identities.forEach(function(identity) {
      emit(identity, null);
    });
  }
}