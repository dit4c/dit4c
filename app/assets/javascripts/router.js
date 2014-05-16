App.Router.map(function() {
  this.resource('app', { path: '/' });
});

App.AppRoute = Ember.Route.extend({
  model: function() {
    return DS.PromiseObject.create({
      promise: new Promise(function(resolve, reject) {
        $.getJSON('/users/current').then(function(json) {
          resolve(json);
        }, function() {
          resolve({});
        });
      })
    });
  }
});