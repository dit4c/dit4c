define([
    'angular',
    './controllers/index',
    './directives/index',
    './filters/index',
    './services/index'
  ], (ng) ->
  'use strict'
  
  app = ng.module('app', [
    'ngRoute',
    'app.services',
    'app.controllers',
    'app.filters',
    'app.directives'
  ])
  
  app.config(['$locationProvider', ($locationProvider) ->
    # Configuring $location to HTML5 mode
    $locationProvider.html5Mode(true).hashPrefix('!')
  ])
  
  app
)