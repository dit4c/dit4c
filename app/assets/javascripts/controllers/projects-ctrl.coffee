define(['./module'], (controllers) ->
  'use strict'
  
  controllers.controller('ProjectsCtrl', ($scope, $route, $http, $location) ->
    $scope.images = [
      { value: "dit4c/ipython", label: "iPython" },
      { value: "dit4c/rstudio", label: "RStudio" }
    ]
    
    $scope.projects = $route.current.locals.projects
    
    $scope.newProject =
      name: "",
      image: $scope.images[0].value
      active: false
    
    $scope.rootUrl = (name) ->
      "//"+name+"."+$location.host()
    
    $scope.create = () ->
      $http
        .post('/projects', $scope.newProject)
        .then (response) ->
          console.log(response)
          $scope.newProject.name = ""
          $scope.projects.push(response.data)
    
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
    
    refreshProjects = () ->
      $http
        .get('/projects')
        .then (response) ->
          console.log(response.data)
          $scope.projects.length = 0
          response.data.forEach (project) ->
            $scope.projects.push(project)
    
    $scope.delete = (id) ->
      $http
        .delete("/projects/"+id)
        .then refreshProjects
      
  )
)