App.Router.map(function() {
  this.resource('app', { path: '/' });
});

App.AppRoute = Ember.Route.extend({
  model: function() {
    return DS.PromiseObject.create({
      promise: $.getJSON('/users/current')
    });
  }
});