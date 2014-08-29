define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ComputeNodesCtrl', ($scope, $route, $http, $location, $filter) ->
    
    $scope.computeNodes = $route.current.locals.computeNodes
    
    $scope.containers = $route.current.locals.containers

  )
)