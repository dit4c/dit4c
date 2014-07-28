define [], () ->
  # webjars plugin is referenced, so we have to define one
  load: (name, req, onload, config) ->
    # ignore import
    onload({})
