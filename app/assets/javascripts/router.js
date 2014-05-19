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