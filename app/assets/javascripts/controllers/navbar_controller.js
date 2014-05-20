App.NavbarController = Ember.ArrayController.extend({
  user: DS.PromiseObject.create({
    promise: new Promise(function(resolve, reject) {
      $.getJSON('/users/current').then(function(json) {
        // Handle login return
        var referrerCookie = $.cookie('login_referrer');
        if (referrerCookie && window.location.href != referrerCookie) {
          $.removeCookie('login_referrer');
          console.log("Redirecting to "+referrerCookie)
          window.location.href = referrerCookie;
        }
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