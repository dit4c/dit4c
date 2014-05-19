App.Router.reopen({
  location: 'history'
});
App.Router.map(function() {
  this.resource('login', { path: '/login' });
  this.resource('projects', { path: '/projects' });
});

App.IndexRoute = Ember.Route.extend({
  model: function() {
    return {};
  }
});

App.ProjectsRoute = Ember.Route.extend({
  model: function() {
    return this.store.find('project');
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
  }),
  actions: {
    logout: function() {
      var controller = this;
      var user = this.user;
      $.post('/logout').then(function() {
        user.set("content", null);
        controller.transitionToRoute('index');
      });
    }
  }
});