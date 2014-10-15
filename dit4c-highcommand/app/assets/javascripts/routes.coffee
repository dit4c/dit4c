define(['./app'], (app) ->
  'use strict'
  app.config(['$routeProvider', ($routeProvider) ->
    $routeProvider.when('/',
      templateUrl: 'index.html'
      controller: 'IndexCtrl'
    )
    
    $routeProvider.when('/login',
      templateUrl: 'login.html'
      controller: 'LoginCtrl',
      resolve: {
        notLoggedIn: (AuthSrv, $location) ->
          AuthSrv.updateUser().then((user) ->
            if user
              $location.path('/containers').replace()
          )
      }
    )
    
    $routeProvider.when('/logout',
      redirectTo: '/',
      resolve: {
        logout: (AuthSrv) ->
          AuthSrv.logout()
      }
    )
    
    $routeProvider.when('/containers',
      templateUrl: 'containers.html'
      controller: 'ContainersCtrl',
      resolve:
        containers: ($http) ->
          $http
            .get('/containers')
            .then (response) ->
              response.data
        computeNodes: ($http, $q) ->
          $http
            .get('/compute-nodes')
            .then (response) ->
              nodes = response.data
              reqs = nodes.map (node) ->
                $http
                  .get('/compute-nodes/'+node.id+'/images')
                  .then (response) ->
                    node.images = response.data.filter (image) ->
                      # Only include downloaded images
                      image.metadata
              $q.all(reqs)
                .then () ->
                  nodes.filter (node) ->
                    node.images.length > 0
    )
    
    $routeProvider.when('/compute-nodes',
      templateUrl: 'compute-nodes.html'
      controller: 'ComputeNodesCtrl',
      resolve:
        computeNodes: ($http) ->
          $http
            .get('/compute-nodes')
            .then (response) ->
              response.data
    )
    
    $routeProvider.when('/notfound',
      templateUrl: 'notfound.html'
    )

    $routeProvider.otherwise(
      redirectTo: '/notfound'
    )
  ])
)