App.NewProjectName = Ember.TextField.extend({
  keyUp: function(e) {
    var self = this;
    var value = this.get('value');
    Ember.$.getJSON("/projects/checkNew?name="+value,
      function(data) {
        var $inputGroup = self.$().parent();
        if (data.valid) {
          // TODO: clear errors
          $inputGroup
            .addClass("has-success")
            .removeClass("has-error");
          $inputGroup.find('.form-control-feedback')
            .addClass("glyphicon-ok")
            .removeClass("glyphicon-remove")
            .removeAttr('title')
            .css('cursor', 'auto');
        } else {
          $inputGroup
            .addClass("has-error")
            .removeClass("has-success");
          $inputGroup.find('.form-control-feedback')
            .addClass("glyphicon-remove")
            .removeClass("glyphicon-ok")
            .attr('title', data.reason)
            .css('cursor', 'pointer');
        }
      });
  }
});