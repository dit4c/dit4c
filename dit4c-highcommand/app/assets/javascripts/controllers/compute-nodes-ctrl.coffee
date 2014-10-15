define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('ComputeNodesCtrl', ($scope, $route, $http, $location, $filter, $q) ->

    $scope.tokens = {}
    $scope.images = {}
    $scope.containers = {}
    $scope.owners = {}
    $scope.users = {}

    $scope.computeNodes = $route.current.locals.computeNodes

    $scope.relevantComputeNode = (node) ->
      node.usable or node.owned

    $scope.accessFormError = null
    $scope.accessForm =
      code: ''

    $scope.addFormError = null
    $scope.addForm =
      name: ''
      managementUrl: ''
      backend:
        host: ''
        port: 80
        scheme: 'http'

    $scope.keyCodeFilter = (e, max, chunkSize) ->
      # Only apply to printable characters
      return if (e.which < 32 || e.ctrlKey || e.altKey)
      e.preventDefault()
      # Work on access form
      obj = $scope.accessForm
      # Only allow [A-Z0-9], and insert dashes appropriately
      numeric = e.which >= 48 && e.which <= 57
      upperAlpha = e.which >= 65 && e.which <= 90
      lowerAlpha = e.which >= 97 && e.which <= 122
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
          addChar(e.which)
        when lowerAlpha
          addChar(e.which - 32)

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
          $scope.addFormError = response

    $scope.populateEditForm = (node) ->
      node.editForm =
        name: node.name
        managementUrl: node.managementUrl
        backend: node.backend

    $scope.clearEditForm = (node) ->
      node.editForm = undefined

    $scope.resolveUsers = (node, userIds) ->
      $scope.users[node.id].filter (user) ->
        userIds.indexOf(user.id) != -1

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

    $scope.addImage = (node, newImage) ->
      $http
        .post('/compute-nodes/'+node.id+"/images", newImage)
        .success (token) ->
          $scope.images[node.id] ||= []
          $scope.images[node.id].push(token)

    $scope.pullImage = (node, image) ->
      $http
        .post('/compute-nodes/'+node.id+"/images/"+image.id+"/pull")
        .success () ->
          $scope.images[node.id]
            .filter (i) -> i.id == image.id
            .forEach (i) -> i.pulling = true

    $scope.removeToken = (node, token) ->
      $http
        .delete('/compute-nodes/'+node.id+"/tokens/"+token.code)
        .success () ->
          $scope.tokens[node.id] ||= []
          $scope.tokens[node.id] = $scope.tokens[node.id].filter (t) ->
            t.code != token.code

    $scope.removeImage = (node, image) ->
      $http
        .delete('/compute-nodes/'+node.id+"/images/"+image.id)
        .success () ->
          $scope.images[node.id] ||= []
          $scope.images[node.id] = $scope.images[node.id].filter (i) ->
            i.id != image.id

    $scope.removeContainer = (node, container) ->
      $http
        .delete('/compute-nodes/'+node.id+"/containers/"+container.id)
        .success () ->
          $scope.containers[node.id] ||= []
          $scope.containers[node.id] = $scope.containers[node.id].filter (c) ->
            c.id != container.id

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

    refreshImages = (node) ->
      $http
        .get('/compute-nodes/'+node.id+"/images")
        .then (response) ->
          $scope.images[node.id] = []
          response.data.forEach (image) ->
            $scope.images[node.id].push(image)

    refreshContainers = (node) ->
      $http
        .get('/compute-nodes/'+node.id+"/containers")
        .then (response) ->
          $scope.containers[node.id] = []
          response.data.forEach (container) ->
            $scope.containers[node.id].push(container)

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
        refreshImages(node),
        refreshContainers(node),
        refreshOwners(node),
        refreshUsers(node)])

    $route.current.locals.computeNodes
      .filter (node) ->
        node.owned
      .forEach refreshAssociatedCollections

  )
)
