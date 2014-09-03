define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ComputeNodesCtrl', ($scope, $route, $http, $location, $filter, $q) ->
    
    $scope.tokens = {}
    $scope.owners = {}
    $scope.users = {}
    
    $scope.computeNodes = $route.current.locals.computeNodes
    
    $scope.containers = $route.current.locals.containers
    $scope.relevantComputeNode = (node) ->
      node.usable or node.owned
    
    $scope.accessFormError = null
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
  
    $scope.populateEditForm = (node) ->
      node.editForm =
        name: node.name
        managementUrl: node.managementUrl
        backend: node.backend
  
    $scope.clearEditForm = (node) ->
      node.editForm = undefined
  
    $scope.updateNode = (node) ->
      $http
        .put('/compute-nodes/'+node.id, node.editForm)
        .then (response) ->
          refreshComputeNodes()

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
          refreshComputeNodes().then () ->
            $scope.computeNodes
              .filter (node) ->
                node.owned and node.id == nodeId
              .forEach refreshAssociatedCollections
        .error (response) ->
          $scope.accessFormError = response
      
    $scope.addToken = (node, type) ->
      $http
        .post('/compute-nodes/'+node.id+"/tokens",
          type: type
        )
        .success (token) ->
          $scope.tokens[node.id] ||= []
          $scope.tokens[node.id].push(token)

    $scope.removeToken = (node, token) ->
      $http
        .delete('/compute-nodes/'+node.id+"/tokens/"+token.code)
        .success () ->
          $scope.tokens[node.id] ||= []
          $scope.tokens[node.id] = $scope.tokens[node.id].filter (t) ->
            t.code != token.code

    $scope.removeOwner = (node, owner) ->
      $http
        .delete('/compute-nodes/'+node.id+"/owners/"+owner.id)
        .success () ->
          $scope.owners[node.id] ||= []
          $scope.owners[node.id] = $scope.owners[node.id].filter (o) ->
            o.id != owner.id

    $scope.removeUser = (node, user) ->
      $http
        .delete('/compute-nodes/'+node.id+"/users/"+user.id)
        .success () ->
          $scope.users[node.id] ||= []
          $scope.users[node.id] = $scope.users[node.id].filter (u) ->
            u.id != user.id

    refreshTokens = (node) ->
      $http
        .get('/compute-nodes/'+node.id+"/tokens")
        .then (response) ->
          $scope.tokens[node.id] = []
          response.data.forEach (token) ->
            $scope.tokens[node.id].push(token)
    
    refreshOwners = (node) ->
      $http
        .get('/compute-nodes/'+node.id+"/owners")
        .then (response) ->
          $scope.owners[node.id] = []
          response.data.forEach (owner) ->
            $scope.owners[node.id].push(owner)

    refreshUsers = (node) ->
      $http
        .get('/compute-nodes/'+node.id+"/users")
        .then (response) ->
          $scope.users[node.id] = []
          response.data.forEach (user) ->
            $scope.users[node.id].push(user)
    
    refreshAssociatedCollections = (node) ->
      $q.all([
        refreshTokens(node),
        refreshOwners(node),
        refreshUsers(node)])
    
    $route.current.locals.computeNodes
      .filter (node) ->
        node.owned
      .forEach refreshAssociatedCollections

  )
)