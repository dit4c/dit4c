define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('AccountMergeCtrl', ($scope, $route, $http, $location) ->

    $scope.userA = $route.current.locals.userA
    
    $scope.userB = $route.current.locals.userB
    
    $scope.submit = () ->
      $http
        .post('/account/merge')
        .then (response) ->
          $location.url("/account")
    
    $scope.cancel = () ->
      $http
        .delete('/account/merge')
        .then (response) ->
          $location.url("/account")
    
  )
)
