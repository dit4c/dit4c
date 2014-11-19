define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('ContainersCtrl', ($scope, $route, $http, $location, $filter) ->

    $scope.imageName = (image) ->
      image.repository+":"+image.tag

    $scope.computeNodes = $route.current.locals.computeNodes

    $scope.containers = $route.current.locals.containers

    $scope.newContainer =
      name: ""
      computeNode: null
      image: ""
      active: false

    $scope.closeAddForm = () ->
      isAddFormOpen = false

    $scope.$watch("computeNodes",
      (newValue, oldValue) ->
        if (!$scope.newContainer.computeNode)
          if (newValue.length > 0)
              $scope.newContainer.computeNode = newValue[0]
      , true)

    $scope.rootUrl = (name) ->
      "//"+name+"."+$location.host()

    $scope.create = () ->
      request =
          name: $scope.newContainer.name
          computeNodeId: ($scope.newContainer.computeNode||{}).id,
          image: $scope.newContainer.image
          active: $scope.newContainer.active
      $scope.newContainer.name = ""
      $scope.containers.push(request)
      $http
        .post('/containers', request)
        .success (response) ->
          $scope.containers[$scope.containers.indexOf(request)] = response
        .error (response) ->
          alert(response)
          $scope.containers.splice($scope.containers.indexOf(request), 1)

    containerById = (id) ->
      $scope.containers.filter((container) -> container.id == id)[0]

    updateActiveFn = (active) -> (id) ->
      container = containerById(id)
      container.active = active
      $http
        .put("/containers/"+id, container)
        .then (response) -> container.active = response.data.active

    $scope.turnOn = updateActiveFn(true)
    $scope.turnOff = updateActiveFn(false)

    refreshContainers = () ->
      $http
        .get('/containers')
        .then (response) ->
          $scope.containers.length = 0
          response.data.forEach (container) ->
            $scope.containers.push(container)

    $scope.delete = (id) ->
      $http
        .delete("/containers/"+id)
        .success (response) ->
          refreshContainers()
        .error (response) ->
          alert(response)

    $scope.checkName = (name) ->
      if (name == "")
        $scope.nameCheck = {}
      else
        $http
          .get('/containers/check-new?name='+name)
          .then (response) ->
            $scope.nameCheck = response.data

    $scope.selectFirstImage = (computeNode) ->
      if (computeNode and computeNode.images and computeNode.images.length > 0)
        $scope.newContainer.image = $scope.imageName(computeNode.images[0])
  )
)
