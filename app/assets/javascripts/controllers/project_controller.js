App.ProjectsController = Ember.ArrayController.extend({
  actions: {
    createProject: function() {
      var active = this.get('active') == true;
      // Get the project name set by the "New Project" text field
      var name = this.get('name');
      if (!name.trim()) { return; }

      // Create the new Project model
      var project = this.store.createRecord('project', {
        'name':   name,
        'active': active
      });

      // Clear the "New Project" text field
      this.set('name', '');

      // Save the new model
      project.save();
    },
    startProject: function(projectId) {
      var project = this.store.getById('project', projectId);
      project.set('active', true);
      project.save();
    },
    stopProject: function(projectId) {
      var project = this.store.getById('project', projectId);
      project.set('active', false);
      project.save();
    }
  }
});