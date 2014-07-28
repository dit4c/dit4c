dit4c-gatehouse
===============

Gatehouse interfaces with Docker to provide Nginx with a checking service for `ngx_http_auth_request_module`.

Method of Operation
---------------------------
Authentication requests from this module are directed to `/auth`. Gatehouse looks for a `dit4c-jwt` cookie, which should be a signed JSON web token. 

It then checks that the signature verifies against one of its known public keys, and denies access if it is unsigned or improperly signed.

Gatehouse looks at the JWT payload, and authorizes the request if the list from the `http://dit4c.github.io/authorized_containers` claim contains the requested container. e.g.

    {
      "iss":"http://dit4c.github.io/",
      "iat":1395968627,
      "http://dit4c.github.io/authorized_containers": ["foo", "bar"]
    }

On success, Gatehouse returns the correct port number for Nginx proxy requests to the container.
