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
    this.controllerFor('navbar').user.then(function(obj) {
      var referrerCookie = $.cookie('login_referrer');
      // Check if we have a logged-in user
      if (obj === null) {
        // Handle login landing
        if (!referrerCookie) {
          var referrer = document.referrer;
          if (referrer && window.location.href != referrer) {
            $.cookie('login_referrer', referrer);
          }
        }
      } else {
        // Handle login return
        if (referrerCookie && window.location.href != referrerCookie) {
          $.removeCookie('login_referrer');
          window.location.href = referrerCookie;
        } else {
          controller.transitionToRoute('projects');
        }
      }
    }, function(reason) {
      console.log(reason);
    });
  }
});

App.ProjectsRoute = Ember.Route.extend({
  model: function() {
    return this.store.find('project');
  }
});