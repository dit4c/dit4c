App.NavbarController = Ember.ArrayController.extend({
  user: DS.PromiseObject.create({
    promise: new Promise(function(resolve, reject) {
      $.getJSON('/users/current').then(function(json) {
        resolve(json);
      }, function() {
        resolve(null);
      });
    })
  }),
  actions: {
    logout: function() {
      var controller = this;
      var user = this.user;
      $.post('/logout').then(function() {
        // Clear user object
        controller.set("user", DS.PromiseObject.create({
          promise: Ember.RSVP.resolve(null)
        }));
        controller.transitionToRoute('index');
      });
    }
  }
});