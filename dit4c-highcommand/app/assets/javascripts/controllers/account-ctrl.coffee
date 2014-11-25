define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('AccountCtrl', ($scope, $route, $http, $location) ->

    $scope.user = $route.current.locals.user
  )
)
