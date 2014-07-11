define(['./app'], (app) ->
  'use strict'
  app.config(['$routeProvider', ($routeProvider) ->
    $routeProvider.when('/',
      templateUrl: 'index.html'
      controller: 'IndexCtrl'
    )
    
    $routeProvider.when('/login',
      templateUrl: 'login.html'
      controller: 'LoginCtrl'
    )
    
    $routeProvider.when('/logout',
      redirectTo: '/',
      resolve: {
        logout: (AuthSrv) ->
          AuthSrv.logout()
      }
    )
    
    $routeProvider.when('/projects',
      templateUrl: 'projects.html'
      controller: 'ProjectsCtrl'
    )
    
    $routeProvider.when('/notfound',
      templateUrl: 'notfound.html'
    )

    $routeProvider.otherwise(
      redirectTo: '/notfound'
    )
  ])
)