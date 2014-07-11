define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ApplicationCtrl', ['$scope', 'AuthSrv', ($scope, AuthSrv) ->
    $scope.currentUser = null
    
    AuthSrv.updateUser().then (currentUser) ->
      $scope.currentUser = currentUser
    
    this
  ])
)