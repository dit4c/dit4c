App.ProjectsController = Ember.ArrayController.extend({
  actions: {
    createProject: function() {
      // Get the project name set by the "New Project" text field
      var name = this.get('newProjectName');
      if (!name.trim()) { return; }

      // Create the new Project model
      var project = this.store.createRecord('project', {
        'name': name,
        'active': false
      });

      // Clear the "New Project" text field
      this.set('newProjectName', '');

      // Save the new model
      project.save();
    }
  }
});