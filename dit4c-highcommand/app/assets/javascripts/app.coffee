define([
    'angular',
    'ui-bootstrap',
    'ui-bootstrap-tpls',
    './controllers/index',
    './directives/index',
    './filters/index',
    './services/index'
  ], (ng) ->
  'use strict'
  
  app = ng.module('app', [
    'ngRoute',
    'ui.bootstrap',
    'app.services',
    'app.controllers',
    'app.filters',
    'app.directives'
  ])
  
  app.config(['$locationProvider', ($locationProvider) ->
    # Configuring $location to HTML5 mode
    $locationProvider.html5Mode(true).hashPrefix('!')
  ])
  
  # Adapted from http://stackoverflow.com/a/19417858/701439
  app.run(['$rootScope', '$location', '$window', ($rootScope, $location, $window) ->
    $rootScope.$on '$routeChangeSuccess', () ->
      $window.ga('send', 'pageview', $location.path()) if ($window.ga)
  ])
  
  app
)