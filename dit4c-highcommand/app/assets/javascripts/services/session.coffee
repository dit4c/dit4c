define(['./module'], (ng) ->
  ng.service('Session', () ->
    this.user = null
    
    this.login = (user) ->
      this.user = user
      
    this.logout = () ->
      this.user = null
    
    this
  )
)