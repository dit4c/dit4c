App.ProjectsController = Ember.ArrayController.extend({
  images: [
    { id: 'dit4c/ipython', name: "iPython" },
    { id: 'dit4c/rstudio', name: 'RStudio' }
  ],
  actions: {
    create: function() {
      var active = this.get('active') == true;
      // Get the project name set by the "New Project" text field
      var name = this.get('name');
      if (!name.trim()) { return; }
      var image = this.get('image');

      // Create the new Project model
      var project = this.store.createRecord('project', {
        'name':   name,
        'image':  image,
        'active': active
      });

      // Clear the "New Project" text field
      this.set('name', '');

      // Save the new model
      project.save().then(null, function() {
        // Error callback
        project.deleteRecord();
      });
    },
    start: function(projectId) {
      var project = this.store.getById('project', projectId);
      project.set('active', true);
      project.save();
    },
    stop: function(projectId) {
      var project = this.store.getById('project', projectId);
      project.set('active', false);
      project.save();
    },
    "delete": function(projectId) {
      var project = this.store.getById('project', projectId);
      project.destroyRecord();
    }
  }
});