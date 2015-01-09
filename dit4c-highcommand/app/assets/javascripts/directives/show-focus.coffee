define(['./module'], (directives) ->
  'use strict'

  directives.directive 'showFocus', ($timeout) ->
    (scope, element, attrs) ->
      scope.$watch(attrs.showFocus, (newValue) ->
        $timeout () ->
          newValue && element.focus()
      , true)
)