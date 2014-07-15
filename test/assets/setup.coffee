# Setup requirejs to have the right baseUrl
global.requirejs = require("requirejs")

global.webjars =
  path: (webjar, lib) -> '../lib/'+webjar+'/'+lib

requirejs.config(
  nodeRequire: require
  baseUrl: __dirname + "/javascripts"
)

benv = require(__dirname + '/lib/benv.js')
beforeEach (done) ->
  benv.setup () ->
    # window, document objects are setup
    done()
  

importWebJAR = (webjar) ->
  require(__dirname + '/lib/' + webjar + '/webjars-requirejs.js')

importWebJAR('angularjs')
importWebJAR('bootstrap')
importWebJAR('jquery')

global.assert = require("assert")