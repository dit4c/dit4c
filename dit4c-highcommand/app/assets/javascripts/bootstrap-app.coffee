define([
    'require',
    'angular',
    'angular-route',
    'app',
    './routes'
  ], (require, ng) ->
  'use strict'
  
  require(['requirejs-domready!'], (document) ->
    try
      ng.bootstrap(document, ['app'])
    catch e
      console.log(e.message)
      console.debug(e)
      throw e
  )
)