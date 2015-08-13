define(['./module'], (controllers) ->
  'use strict'

  controllers.controller('ContainersCtrl', ($scope, $route, $http, $location, $filter) ->

    $scope.imageName = (image) ->
      image.repository+":"+image.tag

    $scope.computeNodes = $route.current.locals.computeNodes

    $scope.containers = $route.current.locals.containers

    $scope.isAddFormOpen = false

    $scope.toggleAddForm = (state) ->
      $scope.isAddFormOpen = state

    $scope.newContainer =
      name: ""
      computeNode: null
      image: ""
      active: true

    $scope.$watch("computeNodes",
      (newValue, oldValue) ->
        if (!$scope.newContainer.computeNode)
          if (newValue.length > 0)
              $scope.newContainer.computeNode = newValue[0]
      , true)

    $scope.containerRedirectUrl = (id) ->
      "/containers/"+id+"/redirect"

    $scope.containerDownloadUrl = (id) ->
      "/containers/"+id+"/export"

    $scope.create = () ->
      request =
          name: $scope.newContainer.name
          computeNodeId: ($scope.newContainer.computeNode||{}).id,
          image: $scope.newContainer.image
          active: $scope.newContainer.active
      $scope.newContainer.name = ""
      $scope.toggleAddForm(false)
      $scope.containers.push(request)
      $http
        .post('/containers', request)
        .success (response) ->
          $scope.containers[$scope.containers.indexOf(request)] = response
        .error (response) ->
          alert(response)
          $scope.containers.splice($scope.containers.indexOf(request), 1)

    $scope.update = (container) ->
      if (container.name != '')
        container.editing = false
        $http
          .put("/containers/"+container.id, container)
          .then (response) ->
            replaceContainer(response.data)
      else
        false

    containerById = (id) ->
      $scope.containers.filter((container) -> container.id == id)[0]

    replaceContainer = (container) ->
      angular.extend(containerById(container.id), container)

    updateActiveFn = (active) -> (id) ->
      container = containerById(id)
      container.active = active
      $http
        .put("/containers/"+id, container)
        .then (response) -> container.active = response.data.active

    $scope.turnOn = updateActiveFn(true)
    $scope.turnOff = updateActiveFn(false)

    refreshContainer = (id) ->
      $http
        .get('/containers/'+id)
        .then (response) ->
          replaceContainer(response.data)

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

    $scope.selectFirstImage = (computeNode) ->
      if (computeNode and computeNode.images and computeNode.images.length > 0)
        $scope.newContainer.image = $scope.imageName(computeNode.images[0])
  )
)
