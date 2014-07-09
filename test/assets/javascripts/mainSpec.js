describe("main", function() {
  it('should do nothing for the moment', function(done) {
    requirejs(['main'], function(main) {
      assert.equal(main.doNothing, true);
      done();
    });
  });
});