# Setup requirejs to have the right baseUrl
global.requirejs = require("requirejs")

requirejs.config(
  nodeRequire: require
  baseUrl: __dirname + "/javascripts"
)

global.assert = require("assert")