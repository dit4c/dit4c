@()

// Simple view to lookup by type
function(doc) {
  emit(doc.type, null);
}