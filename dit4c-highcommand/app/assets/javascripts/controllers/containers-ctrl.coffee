define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ContainersCtrl', ($scope, $route, $http, $location) ->
    $scope.images = [
      { value: "dit4c/dit4c-container-base", label: "Base" },
      { value: "dit4c/dit4c-container-ipython", label: "iPython" },
      { value: "dit4c/dit4c-container-rstudio", label: "RStudio" }
    ]
    
    $scope.computeNodes = $route.current.locals.computeNodes
    
    $scope.containers = $route.current.locals.containers
    
    $scope.newContainer =
      name: "",
      image: $scope.images[0].value
      active: false
    
    $scope.rootUrl = (name) ->
      "//"+name+"."+$location.host()
    
    $scope.create = () ->
      $http
        .post('/containers', $scope.newContainer)
        .then (response) ->
          $scope.newContainer.name = ""
          $scope.containers.push(response.data)
    
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
        .then refreshContainers
    
    $scope.checkName = (name) ->
      if (name == "")
        $scope.nameCheck = {}
      else
        $http
          .get('/containers/check-new?name='+name)
          .then (response) ->
            $scope.nameCheck = response.data

  )
)