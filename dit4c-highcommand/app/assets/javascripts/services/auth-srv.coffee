define(['./module', './session'], (ng) ->
  ng.factory('AuthSrv', ['$rootScope', '$http', 'Session', ($rootScope, $http, Session) ->
    authService = {}
    
    authService.updateUser = () ->
      $http
        .get('/users/current')
        .then (response) ->
          Session.login(response.data)
        .catch(() -> Session.logout())
        .finally(() -> 
          $rootScope.$broadcast('updateCurrentUser', Session.user)
          Session.user
        )
    
    authService.logout = () ->
      $http
        .post('/logout')
        .then(authService.updateUser)
    
    authService
  ])
)