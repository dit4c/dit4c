define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ProjectsCtrl', ($scope, $route, $http) ->
    $scope.projects = $route.current.locals.projects
    
    projectById = (id) ->
      $scope.projects.filter((project) -> project.id == id)[0]
    
    updateActiveFn = (active) -> (id) ->
      project = projectById(id)
      project.active = active
      $http
        .put("/projects/"+id, project)
        .then (response) -> project.active = response.data.active
    
    $scope.turnOn = updateActiveFn(true)
    $scope.turnOff = updateActiveFn(false)
  )
)