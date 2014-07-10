describe "main", () ->
  it 'should do nothing for the moment', (done) ->
    requirejs ['main'], (main) ->
      assert.equal(main.doNothing, true)
      done()