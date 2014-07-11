define(['./module', './session'], (ng) ->
  ng.factory('AuthSrv', ['$http', 'Session', ($http, Session) ->
    authService = {}
    
    authService.updateUser = () ->
      $http
        .get('/users/current')
        .then (response) ->
          Session.login(response.data)
        .catch(() -> Session.logout())
        .finally(() -> Session.user)
    
    authService.logout = () ->
      $http
        .post('/logout')
        .then(authService.updateUser)
    
    authService
  ])
)