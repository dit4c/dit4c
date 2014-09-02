define(['./module'], (filters) ->
  'use strict'
  
  chunkF = (size) ->
    chunk = (str) ->
      if (str.length <= size)
        str
      else
        [str.slice(0, size)].concat(chunk(str.slice(size), size))
    chunk
  
  filters.filter 'accessToken', () ->
    (input, chunkSize) ->
      chunkF(chunkSize)(input).join("-")
)