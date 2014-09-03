define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ComputeNodesCtrl', ($scope, $route, $http, $location, $filter) ->
    
    $scope.tokens = {}
    
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
    
    refreshComputeNodes = () ->
      $http
        .get('/compute-nodes')
        .then (response) ->
          $scope.computeNodes.length = 0
          response.data.forEach (computeNode) ->
            $scope.computeNodes.push(computeNode)
    
    $scope.addNode = () ->
      $http
        .post('/compute-nodes', $scope.addForm)
        .success (response) ->
          refreshComputeNodes()
        .error (response) ->
          # TODO: Handle errors
          console.log(response)
          
    $scope.removeNode = (node) ->
      $http
        .delete('/compute-nodes/'+node.id)
        .then (response) ->
          refreshComputeNodes()
      
    $scope.claimNode = () ->
      nodeId = $scope.accessForm.node.id
      code = $scope.accessForm.code.replace(/-/g,'')
      $http
        .post('/compute-nodes/'+nodeId+"/redeem-token?code="+code)
        .success (response) ->
          refreshComputeNodes()
        .error (response) ->
          # TODO: Handle errors
          console.log(response)
      
    $scope.addToken = (node) ->
      $http
        .post('/compute-nodes/'+node.id+"/tokens",
          type: 'share'
        )
        .then (response) ->
          token = response.data
          $scope.tokens[node.id] ||= []
          $scope.tokens[node.id].push(token)
          
    $scope.removeToken = (node, token) ->
      $http
        .delete('/compute-nodes/'+node.id+"/tokens/"+token.code)
        .then (response) ->
          $scope.tokens[node.id] ||= []
          $scope.tokens[node.id] = $scope.tokens[node.id].filter (t) ->
            t.code != token.code

    $route.current.locals.computeNodes
      .filter (node) ->
        node.owned
      .forEach (node) ->
        $http
          .get('/compute-nodes/'+node.id+"/tokens")
          .then (response) ->
            $scope.tokens[node.id] = []
            response.data.forEach (token) ->
              $scope.tokens[node.id].push(token)

  )
)