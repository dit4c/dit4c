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
    browsers: ['Chrome', 'PhantomJS'],
    plugins: [
      'karma-chrome-launcher',
      'karma-coffee-preprocessor',
      'karma-junit-reporter',
      'karma-mocha',
      'karma-phantomjs-launcher',
      'karma-requirejs',
      'karma-sinon-chai'
    ],
    junitReporter: {
      outputFile: 'test_out/unit.xml',
      suite: 'unit'
    }
  });
};