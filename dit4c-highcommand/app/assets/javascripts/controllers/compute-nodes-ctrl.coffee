define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ComputeNodesCtrl', ($scope, $route, $http, $location, $filter) ->
    
    $scope.computeNodes = $route.current.locals.computeNodes
    
    $scope.containers = $route.current.locals.containers
    
    $scope.accessForm =
      code: ''
      
    $scope.addForm =
      name: ''
      managementUrl: ''
      backend:
        host: ''
        port: 80
        scheme: 'http'
    
    $scope.keyCodeFilter = (e, max, chunkSize) ->
      e.preventDefault()
      # Work on access form
      obj = $scope.accessForm
      # Only allow [A-Z0-9], and insert dashes appropriately
      numeric = e.keyCode >= 48 && e.keyCode <= 57
      upperAlpha = e.keyCode >= 65 && e.keyCode <= 90
      lowerAlpha = e.keyCode >= 97 && e.keyCode <= 122
      isMax = (str) ->
        str.replace(/-/g,'').length >= max
      dashNext = (str) ->
        !isMax(obj.code) && (new RegExp("[A-Z0-9]{"+chunkSize+"}$")).test(str)
      addChar = (code) ->
        obj.code += String.fromCharCode(code)
        obj.code += '-' if dashNext(obj.code)
      # Cannot exceed maximum length
      return if isMax(obj.code)
      switch
        when (numeric || upperAlpha)
          addChar(e.keyCode)
        when lowerAlpha
          addChar(e.keyCode - 32)
    
    $scope.addNode = () ->
      console.log($scope.addForm)

  )
)