var allTestFiles = [];
var allRequireFiles = [];
var TEST_REGEXP = /(spec|test)\.js$/i;
var REQUIRE_REGEXP = /webjars-requirejs\.js$/;

var webjars = {};
webjars.path = function(webjar, file) {
  return "../lib/"+ webjar + "/" + file;
};

var pathToModule = function(path) {
  return path.replace(/^\/base\//, '../').replace(/\.js$/, '');
};

Object.keys(window.__karma__.files).forEach(function(file) {
  if (TEST_REGEXP.test(file)) {
    // Normalize paths to RequireJS module names.
    allTestFiles.push(pathToModule(file));
  }
  if (REQUIRE_REGEXP.test(file)) {
    // Normalize paths to RequireJS module names.
    allRequireFiles.push(pathToModule(file));
  }
});

console.log(allRequireFiles.concat(allTestFiles));

require.config({
  // Karma serves files under /base, which is the basePath from your config file
  baseUrl: '/base/src',

  // dynamically load all test files
  deps: allRequireFiles.concat(allTestFiles),
  
  paths: {
    'webjars': '../test/webjars'
  },

  callback: window.__karma__.start
  
});
