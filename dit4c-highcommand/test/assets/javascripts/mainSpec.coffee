describe "main", () ->
  it 'should include without errors', (done) ->
    requirejs ['main'], (main) ->
      done()