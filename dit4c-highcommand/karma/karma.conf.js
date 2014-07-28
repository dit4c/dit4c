module.exports = function (config) {
  config.set({
    basePath : './',
    files: [
      { included: false, pattern: 'lib/**/*.js' },
      { included: false, pattern: 'src/**/*.coffee' },
      { included: false, pattern: 'test/**/*.coffee'},
      'test-main.js'
    ],
    preprocessors: {
      '**/*.coffee': ['coffee']
    },
    autoWatch: true,
    frameworks: ['mocha', 'requirejs', 'sinon-chai'],
    browsers: ['PhantomJS'],
    reporters: ['mocha'],
    plugins: [
      'karma-coffee-preprocessor',
      'karma-mocha',
      'karma-mocha-reporter',
      'karma-phantomjs-launcher',
      'karma-requirejs',
      'karma-sinon-chai'
    ]
  });
};