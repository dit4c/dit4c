App.Router.reopen({
  location: 'history'
});
App.Router.map(function() {
  this.resource('login', { path: '/login' });
});

App.IndexRoute = Ember.Route.extend({
  model: function() {
    return {};
  }
});

App.NavbarController = Ember.ArrayController.extend({
  user: DS.PromiseObject.create({
    promise: new Promise(function(resolve, reject) {
      $.getJSON('/users/current').then(function(json) {
        resolve(json);
      }, function() {
        resolve({});
      });
    })
  })
});