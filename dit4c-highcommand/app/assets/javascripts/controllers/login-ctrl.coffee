define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('LoginCtrl', ($scope) ->
    $scope.isLoginPage = true
  )
)
