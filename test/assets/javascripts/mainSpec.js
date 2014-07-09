var assert = require("assert");

describe("main", function() {
  it('should do nothing for the moment', function() {
    var main = require('./main');
    assert.equal(main.doingNothing, true);
  });
});