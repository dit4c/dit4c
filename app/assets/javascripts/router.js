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

App.LoginRoute = Ember.Route.extend({
  setupController: function(controller, model) {
    var referrerCookie = $.cookie('login_referrer');
    if (!referrerCookie) {
      var referrer = document.referrer;
      if (referrer && window.location.href != referrer) {
        console.log("Setting login referrer to "+referrer)
        $.cookie('login_referrer', referrer);
      }
    }
  }
})

App.ProjectsRoute = Ember.Route.extend({
  model: function() {
    return this.store.find('project');
  }
});