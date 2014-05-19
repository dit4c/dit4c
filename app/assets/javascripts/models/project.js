App.Project = DS.Model.extend({
  name: DS.attr('string'),
  active: DS.attr('boolean'),
  rootUrl: function() {
    return "//"+this.get('name')+"."+window.location.host;
  }.property('name')
});