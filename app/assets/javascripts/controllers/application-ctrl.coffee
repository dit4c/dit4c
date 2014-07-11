define(['./module'], (controllers) ->
  'use strict'
  
  ctrl = controllers.controller('ApplicationCtrl', ['$scope', 'AuthSrv', ($scope, AuthSrv) ->
    
    $scope.currentUser = null
    
    $scope.$on 'updateCurrentUser', (event, value) ->
      $scope.currentUser = value
    
    AuthSrv.updateUser()
  ])
)